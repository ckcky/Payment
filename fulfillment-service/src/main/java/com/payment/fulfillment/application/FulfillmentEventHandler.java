package com.payment.fulfillment.application;

import com.payment.common.core.ModuleNames;
import com.payment.common.core.event.DomainEventHandler;
import com.payment.common.core.event.DomainEventPublisher;
import com.payment.common.dto.event.FulfillmentCompleted;
import com.payment.common.dto.event.FulfillmentFailed;
import com.payment.common.dto.event.PaymentSucceeded;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import org.springframework.stereotype.Component;

/**
 * 消费 Payment 模块公开的 {@link PaymentSucceeded} 事实，创建幂等履约任务。
 *
 * <p>「支付成功」只触发履约，不决定最终履约状态；重复事件（同 paymentId）不产生第二条履约、
 * 不发布第二个事件。</p>
 */
@Component
public class FulfillmentEventHandler implements DomainEventHandler<PaymentSucceeded> {

    private final FulfillmentRepository repository;
    private final DomainEventPublisher publisher;

    public FulfillmentEventHandler(FulfillmentRepository repository, DomainEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Override
    public String eventType() {
        return PaymentSucceeded.EVENT_TYPE;
    }

    @Override
    public void handle(PaymentSucceeded event) {
        onPaymentSucceeded(event);
    }

    public void onPaymentSucceeded(PaymentSucceeded event) {
        // 幂等：同一支付成功事件只处理一次。
        if (repository.findBySourcePaymentId(event.getPaymentId()).isPresent()) {
            return;
        }

        Fulfillment fulfillment = new Fulfillment(
                event.getOrderId(), null, "mock delivery", event.getPaymentId());
        fulfillment.start();

        // 同步 mock 处理（PROCESSING → DELIVERED）。真实实现会在此处调用交付渠道；
        // 未知结果绝不臆断为成功——异常时记录失败并发布 FulfillmentFailed。
        try {
            fulfillment.deliver();
        } catch (RuntimeException ex) {
            fulfillment.fail(ex.getMessage());
            Fulfillment saved = repository.save(fulfillment);
            publisher.publish(new FulfillmentFailed(
                    ModuleNames.FULFILLMENT,
                    String.valueOf(saved.getId()),
                    1L,
                    event.getOrderId(),
                    ex.getMessage()));
            return;
        }

        Fulfillment saved = repository.save(fulfillment);
        publisher.publish(new FulfillmentCompleted(
                ModuleNames.FULFILLMENT,
                String.valueOf(saved.getId()),
                1L,
                event.getOrderId(),
                event.getUserId()));
    }
}
