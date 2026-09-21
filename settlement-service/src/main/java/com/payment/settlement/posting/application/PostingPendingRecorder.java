package com.payment.settlement.posting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.settlement.posting.domain.PendingPosting;
import com.payment.settlement.posting.domain.PendingPostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 出站失败台账登记器（spec 034 §9 / plan §2.2）：后置 RPC 失败时把「原请求载荷」落一行
 * PENDING 台账，交由 {@link PostingRetryScheduler} 按退避补投。
 *
 * <p><b>登记纪律</b>：</p>
 * <ul>
 *   <li><b>成功绝不登记</b>——台账是「失败台账」不是「发送日志」，避免每笔成功交易多一行写放大；</li>
 *   <li><b>并发撞键吸收</b>——「先查再插」有竞态，UNIQUE(event_type, source_id) 兜底：
 *     捕获 {@link DuplicateKeyException} 视为「已在窗口内」静默吸收（plan §2.1），不重复登记；</li>
 *   <li><b>绝不反噬主流程</b>——登记自身失败（序列化 / DB 异常）只记 error 指标与日志，
 *     不向上抛：前序事实（支付成功 / 退款成功）永不因后置台账写入失败而回滚（R-1）；
 *     丢失的行由下轮对账（MISSING_POSTING）发现。</li>
 * </ul>
 */
@Component
public class PostingPendingRecorder {

    private static final Logger log = LoggerFactory.getLogger(PostingPendingRecorder.class);
    private static final String MODULE = "posting";

    /** 台账载荷统一 JSON 序列化；纯 record DTO，无 JDK 时间类型，无需注册模块。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PendingPostingRepository repository;
    private final BusinessMetrics metrics;

    public PostingPendingRecorder(PendingPostingRepository repository, BusinessMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /**
     * 登记一次失败（PENDING，retry_count=0）。
     *
     * @param eventType      事件类型（PAYMENT_CAPTURE / REFUND / SETTLEMENT_MERCHANT / ADJUSTMENT
     *                       / ORDER_NOTIFY_SUCCEEDED / ORDER_NOTIFY_REFUND_RESULT）
     * @param sourceType     来源域（PAYMENT / REFUND / ORDER / SETTLEMENT / RECONCILIATION）
     * @param sourceId       来源业务单号（ADR-0063：paymentNo / refundNo / batchNo / adjustNo）
     * @param idempotencyKey 幂等键字符串（记账类 = {eventType}:{sourceId}；通知类同型，仅留痕不外发）
     * @param replayPayload  原请求对象（原样序列化；重放 = 原样重发，不重算派生键，杜绝二次漂移）
     * @param failReason     失败原因（截断到列宽内由调用方保证或此处粗截）
     */
    public void recordFailure(String eventType, String sourceType, String sourceId,
                              String idempotencyKey, Object replayPayload, String failReason) {
        try {
            String payloadJson = MAPPER.writeValueAsString(replayPayload);
            repository.insert(new PendingPosting(eventType, sourceType, sourceId,
                    idempotencyKey, payloadJson));
            log.warn("出站失败已登记台账 event={} sourceId={} reason={}", eventType, sourceId,
                    truncate(failReason));
        } catch (DuplicateKeyException alreadyRecorded) {
            // 同一事实反复失败只留一行：已在台账内的行由重试器按退避补投（plan §2.1）
            log.debug("台账行已存在（并发/重复失败吸收）event={} sourceId={}", eventType, sourceId);
        } catch (Exception ex) {
            // 序列化失败 / DB 异常一并吸收：台账丢失可由下轮对账发现，优于打断业务（plan §7 风险 1）
            metrics.counter("ledger.posting_record_failed", 1.0, "module", MODULE);
            log.error("台账登记失败（不影响主事实，对账兜底）event={} sourceId={} reason={}",
                    eventType, sourceId, ex.getMessage());
        }
    }

    /** 供重放端点反序列化载荷（与登记共用同一 MAPPER，保证往返一致）。 */
    public <T> T readPayload(String payloadJson, Class<T> type) {
        try {
            return MAPPER.readValue(payloadJson, type);
        } catch (Exception ex) {
            throw new IllegalStateException("台账载荷反序列化失败: " + ex.getMessage(), ex);
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }
}
