package com.payment.entitlement.application;

import com.payment.common.core.ModuleNames;
import com.payment.common.core.event.DomainEvent;
import com.payment.common.core.event.DomainEventPublisher;
import com.payment.common.dto.event.EntitlementGranted;
import com.payment.common.dto.event.FulfillmentCompleted;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementStatus;
import com.payment.entitlement.infra.InMemoryEntitlementRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件处理器：首次履约完成创建一条 AVAILABLE 权益并发布一次授予事件；
 * 重复投递同一履约完成事件是幂等 no-op。
 */
class EntitlementEventHandlerTest {

    private static final class CapturingPublisher implements DomainEventPublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }

        List<DomainEvent> events() {
            return events;
        }
    }

    @Test
    void firstCompletionGrantsOnceAndRepeatIsNoOp() {
        InMemoryEntitlementRepository repository = new InMemoryEntitlementRepository();
        CapturingPublisher publisher = new CapturingPublisher();
        EntitlementEventHandler handler = new EntitlementEventHandler(repository, publisher);

        FulfillmentCompleted event = new FulfillmentCompleted(
                ModuleNames.FULFILLMENT, "ful_1", 1L, "order_1", "user_1");

        handler.onFulfillmentCompleted(event);

        Entitlement entitlement = repository.findBySourceFulfillmentId("ful_1").orElseThrow();
        assertThat(entitlement.getStatus()).isEqualTo(EntitlementStatus.AVAILABLE);
        assertThat(entitlement.getUserId()).isEqualTo("user_1");
        assertThat(entitlement.getOrderId()).isEqualTo("order_1");
        assertThat(entitlement.getAvailableQuantity()).isEqualTo(1);

        assertThat(publisher.events()).hasSize(1);
        assertThat(publisher.events().get(0)).isInstanceOf(EntitlementGranted.class);
        EntitlementGranted granted = (EntitlementGranted) publisher.events().get(0);
        assertThat(granted.getSourceModule()).isEqualTo(ModuleNames.ENTITLEMENT);
        assertThat(granted.getEntitlementId()).isEqualTo(String.valueOf(entitlement.getId()));
        assertThat(granted.getOrderId()).isEqualTo("order_1");

        // 幂等：同一履约完成事件重复投递，不创建新权益、不重复发布授予事件。
        handler.onFulfillmentCompleted(event);

        assertThat(repository.findBySourceFulfillmentId("ful_1").orElseThrow().getId())
                .isEqualTo(entitlement.getId());
        assertThat(publisher.events()).hasSize(1);
    }
}
