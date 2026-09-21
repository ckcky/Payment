package com.payment.common.mq.dlq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DLQ 管理装配（spec 034 §10 / T20）。由 {@code RedisMqAutoConfiguration} {@code @Import}，
 * 随 {@code payment.mq.enabled} 总开关联动（MQ 关闭时 DLQ 管理无意义）。
 *
 * <p>service + gauge 无条件注册（无 web 依赖、纯 Redis 客户端回调）；web 端点的
 * 条件装配在 {@link MqDlqAdminController} 类上（{@code @ConditionalOnClass(RestController)} +
 * {@code payment.mq.dlq-admin.enabled}，默认 false）。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MqDlqAdminProperties.class)
public class MqDlqConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MqDlqAdminService mqDlqAdminService(StringRedisTemplate redis,
                                               ObjectProvider<StructuredAuditLogger> audit) {
        return new MqDlqAdminService(redis, audit.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public MqDlqSizeGauge mqDlqSizeGauge(StringRedisTemplate redis, BusinessMetrics metrics) {
        return new MqDlqSizeGauge(redis, metrics);
    }
}
