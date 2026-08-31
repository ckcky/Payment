package com.payment.refund.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundPostProcessAttempt;
import com.payment.refund.domain.RefundPostProcessAttemptRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 退款后处理统一编排（ADR-0017）：确认退款后依次请求 履约撤销 → 权益吊销 → 记账，
 * 每个目标一次调用落一条 {@link RefundPostProcessAttempt}，失败可独立追踪、可重放。
 *
 * <p>任一目标失败**不回滚**退款成功事实（Saga，禁 2PC）；同步有限重试（默认 3 次、退避可配，
 * 默认 0ms 以免在线链路额外延迟），耗尽后保留失败记录由对账/人工重放。仅对 SUCCEEDED /
 * PARTIALLY_SUCCEEDED 触发（调用方保证）。</p>
 */
@Component
public class RefundPostProcessOrchestrator {

    private static final String MODULE = "refund";
    private static final int MAX_ATTEMPTS = 3;

    private final FulfillmentGateway fulfillment;
    private final EntitlementGateway entitlement;
    private final LedgerPostingGateway ledger;
    private final RefundPostProcessAttemptRepository attempts;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger audit;
    /** 重试退避（毫秒），默认 0（在线链路不额外延迟；生产可配置）。 */
    private long backoffMs;

    public RefundPostProcessOrchestrator(FulfillmentGateway fulfillment, EntitlementGateway entitlement,
                                         LedgerPostingGateway ledger, RefundPostProcessAttemptRepository attempts,
                                         BusinessMetrics metrics, StructuredAuditLogger audit) {
        this.fulfillment = fulfillment;
        this.entitlement = entitlement;
        this.ledger = ledger;
        this.attempts = attempts;
        this.metrics = metrics;
        this.audit = audit;
    }

    /** 触发退款后处理（调用方须保证退款已确认）。 */
    public void process(Refund refund) {
        runStep("FULFILLMENT", refund, () -> fulfillment.notifyRefund(
                new RefundFulfillmentRequest(refund.getId(), refund.getPaymentId(), refund.getOrderId(),
                        refund.getUserId(), refund.getReason())));
        runStep("ENTITLEMENT", refund, () -> entitlement.notifyRefundPostProcess(
                new RefundPostProcessRequest(refund.getId(), refund.getPaymentId(), refund.getOrderId(),
                        refund.getUserId(), refund.getReason())));
        // 记账金额 = 申请金额：ADR-0016（部分退款）已否决，成功退款恒为全额。
        runStep("LEDGER", refund, () -> ledger.postRefundCapture(
                "REFUND:" + refund.getIdempotencyKey(), refund.getId(),
                refund.getAmountMinor(), refund.getCurrencyCode()));
    }

    private void runStep(String target, Refund refund, Runnable action) {
        int tries = 0;
        boolean ok = false;
        String detail = null;
        while (tries < MAX_ATTEMPTS && !ok) {
            tries++;
            try {
                action.run();
                ok = true;
                detail = "ok";
            } catch (RuntimeException ex) {
                detail = ex.getMessage();
                sleepBackoff();
            }
        }
        attempts.save(new RefundPostProcessAttempt(refund.getId(), target, ok ? "SUCCEEDED" : "FAILED", detail, tries));
        if (!ok) {
            metrics.counter("refund.post_process_failed", 1.0, "module", MODULE, "target", target);
            audit.audit("refund.post_process_failed", refund.getIdempotencyKey(), refund.getAmountMinor(),
                    refund.getCurrencyCode(), "POST_PROCESS", target, "refund", String.valueOf(refund.getId()));
        }
    }

    private void sleepBackoff() {
        if (backoffMs > 0) {
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 测试缝隙：设置重试退避（毫秒）。 */
    void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }
}
