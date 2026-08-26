package com.payment.entitlement.application;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementStatus;
import com.payment.entitlement.infra.InMemoryEntitlementRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权益应用服务：首次履约完成创建一条 AVAILABLE 权益；
 * 重复投递同一 fulfillmentId 幂等返回原权益，不新建第二条。
 */
class EntitlementApplicationServiceTest {

    private static final FulfillmentCompletedRequest REQUEST =
            new FulfillmentCompletedRequest(1L, "order_1", "user_1");

    @Test
    void firstCompletionGrantsAvailableEntitlement() {
        InMemoryEntitlementRepository repository = new InMemoryEntitlementRepository();
        EntitlementApplicationService service =
                new EntitlementApplicationService(repository, new NoopBusinessMetrics());

        Entitlement e = service.grantOnFulfillmentCompleted(REQUEST);

        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.AVAILABLE);
        assertThat(e.getUserId()).isEqualTo("user_1");
        assertThat(e.getOrderId()).isEqualTo("order_1");
        assertThat(e.getAvailableQuantity()).isEqualTo(1);
        assertThat(e.getSourceFulfillmentId()).isEqualTo("1");
        assertThat(repository.findBySourceFulfillmentId("1")).containsSame(e);
    }

    @Test
    void repeatedFulfillmentReturnsSameEntitlement() {
        InMemoryEntitlementRepository repository = new InMemoryEntitlementRepository();
        EntitlementApplicationService service =
                new EntitlementApplicationService(repository, new NoopBusinessMetrics());

        Entitlement first = service.grantOnFulfillmentCompleted(REQUEST);
        Entitlement second = service.grantOnFulfillmentCompleted(REQUEST);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(repository.findBySourceFulfillmentId("1")).containsSame(first);
    }
}
