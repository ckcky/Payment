package com.payment.payment.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.MqConsumerRuntime;
import com.payment.common.mq.MqProperties;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.StreamConsumer;
import com.payment.common.mq.TransactionChecker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * payment 侧消息通道装配（spec 029 / 批次 C / FR-301、FR-302）。
 *
 * <p>生产：{@code payment.succeeded} / {@code refund.result}；
 * 消费：{@code order.cancelled}（广播，关闭订单下未成功支付单）。</p>
 *
 * <p>{@code payment.mq.enabled=false} 时本配置不生效，生产方回落同步 Feign（FR-306）。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaymentMqConfig {

    /** 消费组名（payment 侧订阅 order.cancelled 广播）。 */
    private static final String GROUP = "payment";

    /** payment 消费 {@code order.cancelled}（广播，本组独立位点）。 */
    @Bean
    public MqConsumerRuntime paymentMqConsumerRuntime(StringRedisTemplate redis, MqProperties props,
                                                     BusinessMetrics metrics, PaymentMqHandlers handlers) {
        StreamConsumer orderCancelled = new StreamConsumer(redis, props, metrics,
                MqTopics.ORDER_CANCELLED, GROUP, "payment-oc",
                handlers::onOrderCancelled, null);
        return new MqConsumerRuntime().register(orderCancelled);
    }

    /** {@code payment.succeeded} 回查：payments 是否 SUCCEEDED。 */
    @Bean
    public TransactionChecker paymentSucceededChecker(PaymentMqHandlers handlers) {
        return handlers.paymentSucceededChecker();
    }

    /** {@code refund.result} 回查：refunds 是否终态。 */
    @Bean
    public TransactionChecker refundResultChecker(PaymentMqHandlers handlers) {
        return handlers.refundResultChecker();
    }
}
