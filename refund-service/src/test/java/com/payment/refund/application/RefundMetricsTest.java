package com.payment.refund.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.infra.InMemoryRefundRepository;
import com.payment.refund.support.RefundTestStack;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款业务指标落地测试（T072）：用 Micrometer 真实落盘并断言计数器增长。
 */
class RefundMetricsTest {

    private final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    private final RefundTestStack.RecordingPaymentRefundGateway payment =
            new RefundTestStack.RecordingPaymentRefundGateway();
    private final RefundTestStack.RecordingEntitlementGateway entitlement =
            new RefundTestStack.RecordingEntitlementGateway();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
    private final StructuredAuditLogger audit = new StructuredAuditLogger();

    private RefundApplicationService appService() {
        return new RefundApplicationService(refunds, payment, entitlement, metrics, audit);
    }

    private CreateRefundCommand cmd() {
        return new CreateRefundCommand("order-1", 1L, "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
    }

    @Test
    void successfulRefundIncrementsCreatedAndSucceeded() {
        Refund refund = appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(registry.get("refund.created").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("refund.succeeded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void duplicateRefundIncrementsDuplicateCounterWithoutSecondCreation() {
        RefundApplicationService service = appService();
        service.createRefund(cmd());
        service.createRefund(cmd());

        assertThat(registry.get("refund.duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("refund.created").counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectedRefundIncrementsRejectedCounter() {
        payment.amount = new com.payment.common.dto.rpc.PaymentAmountQueryResponse(
                1L, "order-1", "user-1", 1000L, "CNY", "SUCCEEDED");

        Refund refund = appService().createRefund(new CreateRefundCommand(
                "order-1", 1L, "user-1", 1200L, "CNY", "customer", "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(registry.get("refund.rejected").counter().count()).isEqualTo(1.0);
    }
}
