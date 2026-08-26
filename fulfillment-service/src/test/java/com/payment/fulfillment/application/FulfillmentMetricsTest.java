package com.payment.fulfillment.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentStatus;
import com.payment.fulfillment.infra.InMemoryFulfillmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履约业务指标（T072）：DELIVERED 计数 {@code fulfillment.completed}；
 * 交付失败迁移至 FAILED 时计数 {@code fulfillment.failed}。
 */
class FulfillmentMetricsTest {

    private static final EntitlementGateway NOOP_GATEWAY =
            request -> new EntitlementGrantedResponse(1L, "GRANTED");

    private static PaymentSucceededRequest request() {
        return new PaymentSucceededRequest(1L, "order_1", "txn_1", "user_1", 1250L, "USD");
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
            Fulfillment newFulfillment(String orderId, String sourcePaymentId) {
                return new Fulfillment(orderId, null, "mock delivery", sourcePaymentId) {
                    @Override
                    public void deliver() {
                        throw new RuntimeException("mock delivery failure");
                    }
                };
            }
        };

        Fulfillment fulfillment = service.acceptPaymentSucceeded(request());

        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(registry.get("fulfillment.failed").counter().count()).isEqualTo(1.0);
    }
}
