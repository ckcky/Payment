package com.payment.entitlement.application;

import com.payment.common.core.ModuleNames;
import com.payment.common.core.event.DomainEventHandler;
import com.payment.common.core.event.DomainEventPublisher;
import com.payment.common.dto.event.EntitlementGranted;
import com.payment.common.dto.event.FulfillmentCompleted;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import org.springframework.stereotype.Component;

/**
 * 消费「履约完成」事件并授予权益。
 *
 * <p>以 {@code sourceFulfillmentId} 为幂等键：重复投递同一履约完成事件
 * 不会创建第二条权益，也不会重复发布授予事件。</p>
 */
@Component
public class EntitlementEventHandler implements DomainEventHandler<FulfillmentCompleted> {

    private final EntitlementRepository repository;
    private final DomainEventPublisher publisher;

    public EntitlementEventHandler(EntitlementRepository repository, DomainEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Override
    public String eventType() {
        return FulfillmentCompleted.EVENT_TYPE;
    }

    @Override
    public void handle(FulfillmentCompleted event) {
        onFulfillmentCompleted(event);
    }

    public void onFulfillmentCompleted(FulfillmentCompleted event) {
        if (repository.findBySourceFulfillmentId(event.getFulfillmentId()).isPresent()) {
            return;
        }
        Entitlement entitlement = new Entitlement(
                event.getUserId(), event.getOrderId(), event.getFulfillmentId(), 1, "default", null);
        entitlement.grant();
        repository.save(entitlement);
        publisher.publish(new EntitlementGranted(
                ModuleNames.ENTITLEMENT, String.valueOf(entitlement.getId()), 1L, event.getOrderId()));
    }
}
