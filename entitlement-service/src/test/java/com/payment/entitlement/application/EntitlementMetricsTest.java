package com.payment.entitlement.application;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementStatus;
import com.payment.entitlement.infra.InMemoryEntitlementRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权益业务指标（T072）：授予成功计数 {@code entitlement.granted}；
 * 授予被拒绝迁移至 FAILED 时计数 {@code entitlement.grant.failed}。
 */
class EntitlementMetricsTest {

    private static final FulfillmentCompletedRequest REQUEST =
            new FulfillmentCompletedRequest(1L, "order_1", "user_1");

    @Test
    void recordsGrantedMetricWhenGranted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntitlementApplicationService service = new EntitlementApplicationService(
                new InMemoryEntitlementRepository(), new MicrometerBusinessMetrics(registry));

        service.grantOnFulfillmentCompleted(REQUEST);

        assertThat(registry.get("entitlement.granted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsGrantFailedMetricWhenGrantRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntitlementApplicationService service = new EntitlementApplicationService(
                new InMemoryEntitlementRepository(), new MicrometerBusinessMetrics(registry)) {
            @Override
            Entitlement newEntitlement(String userId, String orderNo, String sourceFulfillmentId) {
                return new Entitlement(userId, orderNo, sourceFulfillmentId, 1, "default", null) {
                    @Override
                    public void grant() {
                        throw new RuntimeException("grant rejected");
                    }
                };
            }
        };

        Entitlement entitlement = service.grantOnFulfillmentCompleted(REQUEST);

        assertThat(entitlement.getStatus()).isEqualTo(EntitlementStatus.FAILED);
        assertThat(registry.get("entitlement.grant.failed").counter().count()).isEqualTo(1.0);
    }
}
