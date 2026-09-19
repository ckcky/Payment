package com.payment.entitlement.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqConsumerRuntime;
import com.payment.common.mq.MqProperties;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.StreamConsumer;
import com.payment.common.mq.TransactionChecker;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import com.payment.entitlement.domain.EntitlementStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * entitlement 侧消息通道装配（spec 029 / 批次 D / T47-T48、FR-305）。
 *
 * <p>消费 {@code fulfillment.completed}（授予权益）/ {@code fulfillment.revoked}（回收权益）。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EntitlementMqConfig {

    private static final String GROUP = "entitlement";

    @Bean
    public MqConsumerRuntime entitlementMqConsumerRuntime(StringRedisTemplate redis, MqProperties props,
                                                       BusinessMetrics metrics, EntitlementMqHandlers handlers) {
        StreamConsumer completed = new StreamConsumer(redis, props, metrics,
                MqTopics.FULFILLMENT_COMPLETED, GROUP, "et-fc", handlers::onFulfillmentCompleted, null);
        StreamConsumer revoked = new StreamConsumer(redis, props, metrics,
                MqTopics.FULFILLMENT_REVOKED, GROUP, "et-fr", handlers::onFulfillmentRevoked, null);
        return new MqConsumerRuntime().register(completed).register(revoked);
    }
}
