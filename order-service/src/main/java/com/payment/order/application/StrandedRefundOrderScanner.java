package com.payment.order.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.core.trace.TraceContext;
import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.RefundOrderStatus;
import com.payment.order.domain.TransactionRefundRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 搁浅退款单扫描器（spec 034 §7.3 F-5 / T13，order 侧）：扫描「REQUESTED 且未获 payment
 * 受理（paymentRefundNo=null）且 {@code updated_at} 距今超过阈值」的 TXRF，经
 * {@link TransactionApplicationService#retryStrandedRefund} 重放 doCreateRefund 的
 * ②重试分支（B7 已备好：同号复用、不新建第二张 TXRF；payment 侧 {@code RefundPolicy}
 * 累计上限是最后防线）。
 *
 * <p><b>搁浅形态</b>：首调 payment 失败/超时 → TXRF 永久卡 REQUESTED（B7 修复后靠同号重放
 * 推进，但无人再发起）——本扫描器补上「发起」这一环。</p>
 *
 * <p><b>重放有界（spec 红线）</b>：每单累计重放 ≤{@value #MAX_REPLAYS} 次（内存
 * {@code ConcurrentHashMap<refundNo, count>} 计数，重启归零可再扫 2 次——上限是防风暴
 * 不是防重放的总闸，plan §3.2）；达上限写 {@code FINANCIAL_AUDIT} 审计登记并停扫该单，
 * 等待 payment 侧兜底 / 人工收敛。扫描器 MUST NOT 自行判定退款成败（R-3）——只触发重放，
 * 结论恒由 payment 权威回调（on-refund-result）写入。</p>
 */
@Component
public class StrandedRefundOrderScanner {

    private static final Logger log = LoggerFactory.getLogger(StrandedRefundOrderScanner.class);
    private static final String MODULE = "order";

    /** 每单重放上限（spec §7.3：≤2，耗尽转人工登记）。 */
    static final int MAX_REPLAYS = 2;

    private final TransactionRefundRepository transactionRefundRepository;
    private final TransactionApplicationService transactionService;
    private final StructuredAuditLogger auditLogger;
    private final BusinessMetrics metrics;
    private final Duration strandedThreshold;

    /** 每单已重放次数（进程内存态；重启归零属可接受口径，见类注）。 */
    private final Map<String, Integer> replayCounts = new ConcurrentHashMap<>();
    /** 耗尽审计一次性发射守卫（同单只登记一条 FINANCIAL_AUDIT）。 */
    private final Set<String> exhaustedAudited = ConcurrentHashMap.newKeySet();

    public StrandedRefundOrderScanner(TransactionRefundRepository transactionRefundRepository,
                                      TransactionApplicationService transactionService,
                                      StructuredAuditLogger auditLogger,
                                      BusinessMetrics metrics,
                                      @Value("${order.refund.stranded-threshold:PT30S}") Duration strandedThreshold) {
        this.transactionRefundRepository = transactionRefundRepository;
        this.transactionService = transactionService;
        this.auditLogger = auditLogger;
        this.metrics = metrics;
        this.strandedThreshold = strandedThreshold;
    }

    /** 调度入口（spec 034 / 诊断①）：新 traceId 包裹整轮扫描。 */
    @Scheduled(fixedDelayString = "${order.refund.stranded-scan-interval-ms:10000}")
    public void run() {
        TraceContext.runWithNewTrace(() -> scanRound(Instant.now()));
    }

    /** 扫描一轮：返回本轮触发重放的搁浅单数量（测试与观测用）。 */
    public int scanRound(Instant now) {
        int replayed = 0;
        List<RefundOrder> candidates = transactionRefundRepository.findByStatus(RefundOrderStatus.REQUESTED);
        for (RefundOrder txrf : candidates) {
            if (txrf.getPaymentRefundNo() != null) {
                continue; // 已获受理（异常残留）：交由回调/查询收敛，不重放
            }
            Instant persistedAt = txrf.getUpdatedAt();
            if (persistedAt == null) {
                continue; // 未持久化时间戳（理论不可达）：无可判年龄，跳过
            }
            Duration age = Duration.between(persistedAt, now);
            if (age.isNegative() || age.compareTo(strandedThreshold) < 0) {
                continue; // 尚未搁浅（含时钟回拨防御）
            }
            int attempts = replayCounts.merge(txrf.getRefundNo(), 1, Integer::sum);
            if (attempts > MAX_REPLAYS) {
                continue; // 已达上限：停扫该单（审计已在达上限那一轮登记）
            }
            try {
                transactionService.retryStrandedRefund(txrf);
                metrics.counter("order.refund_stranded_replayed", 1.0, "module", MODULE);
                log.warn("搁浅退款单触发重放 txrf={} orderNo={} ageSeconds={} attempt={}/{}",
                        txrf.getRefundNo(), txrf.getOrderNo(), age.toSeconds(), attempts, MAX_REPLAYS);
                replayed++;
            } catch (RuntimeException ex) {
                // 单条失败不中断整轮：warn + 计数，退避由下一轮扫描天然形成
                metrics.counter("order.refund_stranded_replay_failed", 1.0, "module", MODULE);
                log.warn("搁浅退款单重放失败 txrf={} reason={}", txrf.getRefundNo(), ex.getMessage());
            }
            if (attempts >= MAX_REPLAYS && exhaustedAudited.add(txrf.getRefundNo())) {
                // 重放耗尽：ABANDONED-等价的人工登记（spec §7.3 红线），停扫该单
                auditLogger.audit("order.refund_stranded_replay_exhausted", txrf.getRefundNo(),
                        txrf.getAmountMinor(), txrf.getCurrencyCode(),
                        txrf.getStatus().name(), null, "transaction_refund", txrf.getRefundNo());
                metrics.counter("order.refund_stranded_replay_exhausted", 1.0, "module", MODULE);
                log.error("搁浅退款单重放耗尽（等待人工收敛）txrf={} orderNo={}", txrf.getRefundNo(), txrf.getOrderNo());
            }
        }
        return replayed;
    }
}
