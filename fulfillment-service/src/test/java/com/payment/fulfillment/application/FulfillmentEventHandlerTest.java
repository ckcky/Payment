package com.payment.fulfillment.application;

import com.payment.common.core.ModuleNames;
import com.payment.common.core.event.DomainEvent;
import com.payment.common.core.event.DomainEventPublisher;
import com.payment.common.dto.event.FulfillmentCompleted;
import com.payment.common.dto.event.PaymentSucceeded;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import com.payment.fulfillment.domain.FulfillmentStatus;
import com.payment.fulfillment.infra.InMemoryFulfillmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履约事件处理（T042）：PaymentSucceeded → 创建履约并发布 FulfillmentCompleted；
 * 同一支付成功事件幂等——不重复创建、不重复发布。
 */
class FulfillmentEventHandlerTest {

    private FulfillmentRepository repository;
    private CapturingPublisher publisher;
    private FulfillmentEventHandler handler;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFulfillmentRepository();
        publisher = new CapturingPublisher();
        handler = new FulfillmentEventHandler(repository, publisher);
    }

    private static PaymentSucceeded paymentSucceeded() {
        return new PaymentSucceeded(
                ModuleNames.PAYMENT, "pay_1", 1L, "order_1", "txn_1", "user_1", 1250L, "USD");
    }

    @Test
    void firstEventCreatesExactlyOneFulfillmentAndOneCompletedEvent() {
        handler.onPaymentSucceeded(paymentSucceeded());

        Fulfillment fulfillment = repository.findBySourcePaymentId("pay_1").orElseThrow();
        assertThat(fulfillment.getId()).isEqualTo(1L);
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(fulfillment.getOrderId()).isEqualTo("order_1");

        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0)).isInstanceOf(FulfillmentCompleted.class);

        FulfillmentCompleted completed = (FulfillmentCompleted) publisher.events.get(0);
        assertThat(completed.getFulfillmentId()).isEqualTo("1");
        assertThat(completed.getOrderId()).isEqualTo("order_1");
        assertThat(completed.getUserId()).isEqualTo("user_1");
    }

    @Test
    void repeatedEventIsIdempotent() {
        PaymentSucceeded event = paymentSucceeded();

        handler.onPaymentSucceeded(event);
        handler.onPaymentSucceeded(event);

        // 只有一条履约（id=1），不会生成第二条（id=2 不存在）。
        assertThat(repository.findBySourcePaymentId("pay_1")).isPresent();
        assertThat(repository.findById(1L)).isPresent();
        assertThat(repository.findById(2L)).isEmpty();

        // 只发布一个完成事件。
        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0)).isInstanceOf(FulfillmentCompleted.class);
    }

    private static final class CapturingPublisher implements DomainEventPublisher {

        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }
}
