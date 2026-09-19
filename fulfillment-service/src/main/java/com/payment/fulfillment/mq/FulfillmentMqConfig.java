package com.payment.fulfillment.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.MqConsumerRuntime;
import com.payment.common.mq.MqProperties;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.StreamConsumer;
import com.payment.common.mq.TransactionChecker;
import com.payment.common.mq.EventEnvelope;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * fulfillment 侧消息通道装配（spec 029 / 批次 D / T43-T46、FR-304）。
 *
 * <p>消费 {@code order.paid} / {@code refund.succeeded} / {@code order.cancelled}（独立组 {@code fulfillment}）；
 * 生产 {@code fulfillment.completed} / {@code fulfillment.revoked}。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FulfillmentMqConfig {

    private static final String GROUP = "fulfillment";

    @Bean
    public MqConsumerRuntime fulfillmentMqConsumerRuntime(StringRedisTemplate redis, MqProperties props,
                                                        BusinessMetrics metrics, FulfillmentMqHandlers handlers) {
        StreamConsumer orderPaid = new StreamConsumer(redis, props, metrics,
                MqTopics.ORDER_PAID, GROUP, "ff-op", handlers::onOrderPaid, null);
        StreamConsumer refundSucceeded = new StreamConsumer(redis, props, metrics,
                MqTopics.REFUND_SUCCEEDED, GROUP, "ff-rs", handlers::onRefundSucceeded, null);
        StreamConsumer orderCancelled = new StreamConsumer(redis, props, metrics,
                MqTopics.ORDER_CANCELLED, GROUP, "ff-oc", handlers::onOrderCancelled, null);
        return new MqConsumerRuntime()
                .register(orderPaid)
                .register(refundSucceeded)
                .register(orderCancelled);
    }

    /**
     * 回查 checker（FR-302）：{@code fulfillment.completed} / {@code fulfillment.revoked} 的事实判定。
     *
     * <p>真相表 = fulfillments：completed 事件对应履约已 DELIVERED；revoked 事件对应履约
     * 已 CANCELLED 或不存在（撤单场景可能无履约，按 COMMIT 清半消息）。</p>
     */
    @Bean
    public TransactionChecker fulfillmentCompletedChecker(FulfillmentRepository repository) {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return MqTopics.FULFILLMENT_COMPLETED.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                Object raw = e.get("fulfillmentId");
                if (raw == null) {
                    return LocalTxState.ROLLBACK;
                }
                Long id = Long.valueOf(String.valueOf(raw));
                return repository.findById(id)
                        .map(f -> f.getStatus() == com.payment.fulfillment.domain.FulfillmentStatus.DELIVERED
                                ? LocalTxState.COMMIT : LocalTxState.ROLLBACK)
                        .orElse(LocalTxState.ROLLBACK);
            }
        };
    }

    @Bean
    public TransactionChecker fulfillmentRevokedChecker(FulfillmentRepository repository) {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return MqTopics.FULFILLMENT_REVOKED.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                String orderNo = e.str("orderNo");
                if (orderNo == null) {
                    return LocalTxState.ROLLBACK;
                }
                // 撤单后履约已 CANCELLED，或本无履约（INFO 场景）——两者都视为事实成立
                boolean hasCancelled = repository.findByOrderNo(orderNo).stream()
                        .anyMatch(f -> f.getStatus() == com.payment.fulfillment.domain.FulfillmentStatus.CANCELLED);
                boolean none = repository.findByOrderNo(orderNo).isEmpty();
                return hasCancelled || none ? LocalTxState.COMMIT : LocalTxState.ROLLBACK;
            }
        };
    }
}
