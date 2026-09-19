package com.payment.catalog.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.MqConsumerRuntime;
import com.payment.common.mq.MqProperties;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.StreamConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * catalog 侧消息通道装配（spec 029 / 批次 D / T40-T42、FR-303）。
 *
 * <p>订阅三个广播事件（各自独立消费组 {@code catalog}，位点互不影响）：
 * {@code order.paid}（confirm 库存）、{@code refund.succeeded}（秒杀回补）、
 * {@code order.cancelled}（释放预占 + 回补）。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogMqConfig {

    private static final String GROUP = "catalog";

    @Bean
    public MqConsumerRuntime catalogMqConsumerRuntime(StringRedisTemplate redis, MqProperties props,
                                                     BusinessMetrics metrics, CatalogMqHandlers handlers) {
        StreamConsumer orderPaid = new StreamConsumer(redis, props, metrics,
                MqTopics.ORDER_PAID, GROUP, "catalog-op", handlers::onOrderPaid, null);
        StreamConsumer refundSucceeded = new StreamConsumer(redis, props, metrics,
                MqTopics.REFUND_SUCCEEDED, GROUP, "catalog-rs", handlers::onRefundSucceeded, null);
        StreamConsumer orderCancelled = new StreamConsumer(redis, props, metrics,
                MqTopics.ORDER_CANCELLED, GROUP, "catalog-oc", handlers::onOrderCancelled, null);
        return new MqConsumerRuntime()
                .register(orderPaid)
                .register(refundSucceeded)
                .register(orderCancelled);
    }
}
