package com.payment.payment.limit.infra;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.limit.application.LimitExpiryIndex;
import com.payment.payment.limit.application.NoopLimitExpiryIndex;
import com.payment.payment.limit.application.RedisLimitExpiryIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限额子域装配（spec 027 / FR-021~023、FR-036，ADR-0071 D13）。
 *
 * <p><b>过期索引的二选一（INV-9.2）</b>：</p>
 * <ul>
 *   <li>容器里存在 {@link StringRedisTemplate}（即配了 Redis）→ {@link RedisLimitExpiryIndex}；</li>
 *   <li>否则（H2 测试 / 无 Redis 的本地环境）→ {@link NoopLimitExpiryIndex}，其
 *       {@code alive} 返回 {@code null} → 调用方跳过回收 → 在途<b>保持占用</b>。</li>
 * </ul>
 *
 * <p>用 {@code ObjectProvider} 而非 {@code @ConditionalOnBean}：后者在自动配置类中受
 * Bean 定义顺序影响（本类由 {@code @Configuration} 直接扫描加载），会出现「本地有、CI 没有」
 * 的假绿。{@code ObjectProvider} 在<b>调用时</b>解析，语义确定。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LimitProperties.class)
public class LimitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LimitConfiguration.class);

    /** 启动强校验（对齐 ADR-0049 第 2 条：配错了必须响，不静默走默认）。 */
    @Bean
    public LimitPropertiesValidator limitPropertiesValidator(LimitProperties properties) {
        return new LimitPropertiesValidator(properties);
    }

    @Bean
    public LimitExpiryIndex limitExpiryIndex(ObjectProvider<StringRedisTemplate> redisProvider,
                                             LimitProperties properties,
                                             BusinessMetrics metrics) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("未检测到 Redis：额度过期索引降级为 Noop（在途占用保持占用、不拦截支付，INV-9.2）");
            return NoopLimitExpiryIndex.instance();
        }
        log.info("额度过期索引启用 Redis 实现（仅作 TTL 标记，不参与额度计数，INV-9.1）ttl={}",
                properties.getReserveTtl());
        return new RedisLimitExpiryIndex(redis, properties, metrics);
    }

    /**
     * 校验器（Bean 形式而非 {@code @PostConstruct}）：确保校验在属性绑定完成后、
     * 且在其它 Bean 依赖本配置之前执行，顺序确定。
     */
    public static class LimitPropertiesValidator {
        public LimitPropertiesValidator(LimitProperties properties) {
            properties.validate();
        }
    }
}
