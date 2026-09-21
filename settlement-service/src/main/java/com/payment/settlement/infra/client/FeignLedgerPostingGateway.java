package com.payment.settlement.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.common.dto.rpc.AccountingSourceType;
import com.payment.settlement.application.LedgerPostingGateway;
import com.payment.settlement.posting.application.PostingEventTypes;
import com.payment.settlement.posting.application.PostingPendingRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结算 → ledger-service 记账出站网关实现（ADR-0023，031 事件化 / ADR-0077）：
 * 批次收敛为 SUCCEEDED → 发 {@code MERCHANT_SETTLEMENT} 事件；结转分录
 * （借应付商户 / 贷结算应付，负净额反向——H3）全部由账本按 §7.5 规则展开，
 * 本类不再持有科目常量、不再手工拼分录。
 *
 * <p>记账失败**不回滚**批次状态（禁 2PC/XA）：仅记录 {@code ledger.posting_failed}
 * 指标与告警，进入「待记账」清单，由重试/对账兜底（与支付侧 ADR-0009 同口径）。</p>
 *
 * <p>幂等：账本按 {@code MERCHANT_SETTLEMENT:{batchNo}} 派生吸收重复（原则 10）；
 * sourceId 用 batchNo（M1 收编，数值 batchId 不再出服务边界，ADR-0063）。</p>
 */
public class FeignLedgerPostingGateway implements LedgerPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignLedgerPostingGateway.class);
    private static final String MODULE = "settlement";

    private final LedgerFeignClient ledgerClient;
    private final BusinessMetrics metrics;
    private final PostingPendingRecorder pendingRecorder;

    public FeignLedgerPostingGateway(LedgerFeignClient ledgerClient, BusinessMetrics metrics,
                                     PostingPendingRecorder pendingRecorder) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
        this.pendingRecorder = pendingRecorder;
    }

    @Override
    public void postMerchantSettlement(MerchantSettlementFacts facts) {
        AccountingEventRequest request = new AccountingEventRequest(
                AccountingEventType.MERCHANT_SETTLEMENT.name(), AccountingSourceType.SETTLEMENT.name(),
                facts.batchNo(), facts.currencyCode(),
                null, null, null,
                facts.merchantId(), null,
                facts.netMinor(), null, null, null, null, null, null);
        try {
            AccountingEventResponse response = ledgerClient.postEvent(request);
            metrics.counter("ledger.posting_succeeded", 1.0, "module", MODULE);
            log.info("MERCHANT_SETTLEMENT 记账成功 batchNo={} merchantId={} net={} postingId={}",
                    facts.batchNo(), facts.merchantId(), facts.netMinor(), response.postingId());
        } catch (RuntimeException ex) {
            // 记账失败不回滚结算成功事实；记录待记账，交由重试/对账兜底（ADR-0023）
            metrics.counter("ledger.posting_failed", 1.0, "module", MODULE);
            // 出站失败台账（spec 034 §9.1）：登记原请求载荷，交由 PostingRetryScheduler 退避补投；
            // 登记自身失败不抛（R-1：结算成功事实永不因台账写入失败回滚），对账 MISSING_POSTING 兜底
            pendingRecorder.recordFailure(PostingEventTypes.MERCHANT_SETTLEMENT,
                    AccountingSourceType.SETTLEMENT.name(), facts.batchNo(),
                    AccountingEventType.MERCHANT_SETTLEMENT.name() + ":" + facts.batchNo(),
                    request, ex.getMessage());
            log.error("结算记账失败，进入待记账兜底：batchNo={} reason={}", facts.batchNo(), ex.getMessage());
        }
    }
}
