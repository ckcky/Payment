package com.payment.payment.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.common.dto.rpc.AccountingSourceType;
import com.payment.payment.application.LedgerPostingGateway;
import com.payment.posting.application.PostingEventTypes;
import com.payment.posting.application.PostingPendingRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记账出站网关实现（Feature 004 / ADR-0009，031 事件化 / ADR-0077）：
 * 支付成功事实 → 经 Feign 同步 RPC 发 {@code PAYMENT_CAPTURE} 事件，
 * **科目、借贷方向、净额轧差全部由账本经 Posting Rule 决定**——本类不再持有
 * 科目常量、不再算 {@code netMinor = amountMinor - feeMinor}（031 审计 P0 两项的落点）。
 *
 * <p>记账失败**不回滚**支付成功事实（禁 2PC/XA）：失败仅记录 {@code ledger.posting_failed}
 * 指标与告警日志，由对账 {@code MISSING_POSTING}（BLOCKER）兜底。</p>
 *
 * <p>幂等：不传幂等键；账本按 {@code PAYMENT_CAPTURE:{paymentNo}} 派生双唯一约束吸收重复
 * （原则 10，spec 030 B1 双前缀缺陷就此根除——键不再可能被两条路径拼成两个样子）。</p>
 */
public class FeignLedgerPostingGateway implements LedgerPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignLedgerPostingGateway.class);
    private static final String MODULE = "payment";

    private final LedgerFeignClient ledgerClient;
    private final BusinessMetrics metrics;
    /** spec 034 / T7：失败落台账（补偿辅助），成功路径零改动。 */
    private final PostingPendingRecorder pendingRecorder;

    public FeignLedgerPostingGateway(LedgerFeignClient ledgerClient, BusinessMetrics metrics,
                                     PostingPendingRecorder pendingRecorder) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
        this.pendingRecorder = pendingRecorder;
    }

    @Override
    public void postPaymentCapture(PaymentCaptureFacts facts) {
        AccountingEventRequest request = new AccountingEventRequest(
                AccountingEventType.PAYMENT_CAPTURE.name(), AccountingSourceType.PAYMENT.name(),
                facts.paymentNo(), facts.currencyCode(),
                facts.grossAmountMinor(), facts.merchantFeeMinor(), facts.channelFeeMinor(),
                facts.merchantId(), facts.channelCode(),
                null, null, null, null, null, null, null);
        try {
            AccountingEventResponse response = ledgerClient.postEvent(request);
            metrics.counter("ledger.posting_succeeded", 1.0, "module", MODULE);
            log.info("PAYMENT_CAPTURE 记账成功 paymentNo={} merchantId={} channel={} postingId={} entries={}",
                    facts.paymentNo(), facts.merchantId(), facts.channelCode(),
                    response.postingId(), response.entries().size());
        } catch (RuntimeException ex) {
            // 记账失败不回滚支付成功事实；登记台账（退避补投）+ 对账兜底（ADR-0009，spec 034 §9）
            metrics.counter("ledger.posting_failed", 1.0, "module", MODULE);
            pendingRecorder.recordFailure(PostingEventTypes.PAYMENT_CAPTURE,
                    AccountingSourceType.PAYMENT.name(), facts.paymentNo(),
                    AccountingEventType.PAYMENT_CAPTURE.name() + ":" + facts.paymentNo(),
                    request, ex.getMessage());
            log.error("记账失败，进入待记账兜底：paymentNo={} reason={}",
                    facts.paymentNo(), ex.getMessage());
        }
    }
}
