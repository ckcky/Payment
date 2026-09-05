package com.payment.refund.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundPostProcessAttempt;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 退款后处理尝试记录测试（005 T031 收口）：记账 RPC 失败时，
 * 退款成功事实不回滚（ADR-0009 禁 2PC），失败 MUST 落 {@code RefundPostProcessAttempt}
 * （target=LEDGER、status=FAILED、tries=3），供对账/人工兜底追踪。
 */
class RefundPostProcessAttemptTest {

    /** 恒定失败的记账网关：模拟 ledger-service 不可用。 */
    private static final class FailingLedgerGateway implements LedgerPostingGateway {

        int calls = 0;

        @Override
        public void postRefundCapture(String idempotencyKey, String refundNo, long amountMinor, String currencyCode) {
            calls++;
            throw new IllegalStateException("ledger down");
        }
    }

    @Test
    void ledgerFailureKeepsRefundSucceededAndRecordsFailedAttemptWithRetries() {
        RefundTestStack stack = new RefundTestStack();
        FailingLedgerGateway failingLedger = new FailingLedgerGateway();
        RefundPostProcessOrchestrator orchestrator = new RefundPostProcessOrchestrator(
                stack.fulfillment, stack.entitlement, failingLedger, stack.attempts,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        orchestrator.setBackoffMs(0); // 测试不等待退避
        RefundApplicationService service = new RefundApplicationService(
                stack.refunds, stack.payment, orchestrator,
                new NoopBusinessMetrics(), new StructuredAuditLogger());

        Refund refund = service.createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "idem-1", List.of()));

        // 退款成功事实不被记账失败回滚；记账恰好重试 3 次
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(failingLedger.calls).isEqualTo(3);

        List<RefundPostProcessAttempt> attempts = stack.attempts.findByRefundNo(refund.getRefundNo());
        // 每个目标各落一条：FULFILLMENT/ENTITLEMENT 成功，LEDGER 失败
        assertThat(attempts).hasSize(3);
        RefundPostProcessAttempt ledgerAttempt = attempts.stream()
                .filter(a -> "LEDGER".equals(a.getTarget()))
                .findFirst().orElseThrow();
        assertThat(ledgerAttempt.getStatus()).isEqualTo("FAILED");
        assertThat(ledgerAttempt.getAttemptCount()).isEqualTo(3);
        assertThat(ledgerAttempt.getDetail()).contains("ledger down");
    }

    @Test
    void successfulLedgerPostingRecordsSucceededAttempt() {
        RefundTestStack stack = new RefundTestStack(); // RecordingLedgerGateway 默认成功
        Refund refund = stack.appService().createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "idem-1", List.of()));

        List<RefundPostProcessAttempt> attempts = stack.attempts.findByRefundNo(refund.getRefundNo());
        assertThat(attempts).extracting(RefundPostProcessAttempt::getTarget, RefundPostProcessAttempt::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("FULFILLMENT", "SUCCEEDED"),
                        org.assertj.core.groups.Tuple.tuple("ENTITLEMENT", "SUCCEEDED"),
                        org.assertj.core.groups.Tuple.tuple("LEDGER", "SUCCEEDED"));
    }
}
