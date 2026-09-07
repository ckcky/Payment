package com.payment.fulfillment.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentStatus;
import com.payment.fulfillment.infra.InMemoryFulfillmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履约业务指标（T072）：DELIVERED 计数 {@code fulfillment.completed}；
 * 交付失败迁移至 FAILED 时计数 {@code fulfillment.failed}。
 */
class FulfillmentMetricsTest {

    private static final EntitlementGateway NOOP_GATEWAY = new EntitlementGateway() {
        @Override
        public EntitlementGrantedResponse notifyFulfillmentCompleted(FulfillmentCompletedRequest request) {
            return new EntitlementGrantedResponse(1L, "GRANTED");
        }

        @Override
        public com.payment.common.dto.rpc.RefundPostProcessResponse revokeOnRefund(
                com.payment.common.dto.rpc.RefundPostProcessRequest request) {
            return new com.payment.common.dto.rpc.RefundPostProcessResponse(request.refundNo(), "NOOP");
        }
    };

    private static PaymentSucceededRequest request() {
        return new PaymentSucceededRequest("pay-1", "order_1", "txn_1", "user_1", 1250L, "USD",
                List.of(new PaymentSucceededRequest.ItemLine("OI-1", "SKU-1", "商品", 1, 1250L, "USD")));
    }

    @Test
    void recordsCompletedMetricWhenDelivered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FulfillmentApplicationService service = new FulfillmentApplicationService(
                new InMemoryFulfillmentRepository(), NOOP_GATEWAY,
                new MicrometerBusinessMetrics(registry));

        service.acceptPaymentSucceeded(request());

        assertThat(registry.get("fulfillment.completed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsFailedMetricWhenDeliveryFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FulfillmentApplicationService service = new FulfillmentApplicationService(
                new InMemoryFulfillmentRepository(), NOOP_GATEWAY,
                new MicrometerBusinessMetrics(registry)) {
            @Override
            Fulfillment newFulfillment(String orderNo, String orderItemId, String sourcePaymentNo) {
                return new Fulfillment(orderNo, orderItemId, "mock delivery", sourcePaymentNo) {
                    @Override
                    public void deliver() {
                        throw new RuntimeException("mock delivery failure");
                    }
                };
            }
        };

        List<Fulfillment> fulfillments = service.acceptPaymentSucceeded(request());

        assertThat(fulfillments).hasSize(1);
        assertThat(fulfillments.get(0).getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(registry.get("fulfillment.failed").counter().count()).isEqualTo(1.0);
    }
}
