package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.mq.dlq.MqDlqConfiguration;
import com.payment.common.mq.dlq.MqDlqAdminController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 消息通道自动装配（spec 029 / T24 / FR-110）。
 *
 * <p>统一注册 {@link MqProperties}、{@link TransactionalProducer}、{@link HalfMessageScanner}
 * 三个基础 Bean。各服务只需依赖本模块 + 配置 {@code payment.mq.*}，无需重复声明。</p>
 *
 * <p><b>半消息扫描的调度前提</b>：{@link HalfMessageScanner#scan()} 标了
 * {@code @Scheduled}，需要宿主应用开启 {@code @EnableScheduling}。本项目各服务均已为
 * 既有定时任务（订单超时时间轮、对账跑批）开启，故直接生效。</p>
 *
 * <p>{@link StreamConsumer} 因 topic / group / handler 因服务而异，由各服务在自身配置类中
 * 按需构造（见各服务的 {@code *MqConfig}）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(MqProperties.class)
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({MqDlqConfiguration.class, MqDlqAdminController.class})
public class RedisMqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransactionalProducer transactionalProducer(StringRedisTemplate redis, MqProperties props,
                                                       BusinessMetrics metrics,
                                                       StructuredAuditLogger audit,
                                                       org.springframework.core.env.Environment env) {
        String producerName = env.getProperty("spring.application.name", "");
        return new TransactionalProducer(redis, props, metrics, producerName, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public HalfMessageScanner halfMessageScanner(TransactionalProducer producer, MqProperties props,
                                                 BusinessMetrics metrics,
                                                 org.springframework.beans.factory.ObjectProvider<TransactionChecker> checkers) {
        List<TransactionChecker> list = checkers.orderedStream().toList();
        return new HalfMessageScanner(producer, props, metrics, list);
    }

    /**
     * 阻塞读超时不变量守卫（spec 029 回归修复）：启动期校验
     * {@code spring.data.redis.timeout > payment.mq.block-ms}，不满足即 fail-fast。
     *
     * <p>该校验把「消费端静默空转、消息永不被处理」这类极难定位的配置错配，
     * 提前暴露为显式的启动失败。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public MqTimeoutGuard mqTimeoutGuard(MqProperties props, Environment env) {
        java.time.Duration timeout = env.getProperty("spring.data.redis.timeout", java.time.Duration.class,
                java.time.Duration.ofSeconds(60));
        return new MqTimeoutGuard(props, timeout);
    }
}
