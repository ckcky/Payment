package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
public class RedisMqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransactionalProducer transactionalProducer(StringRedisTemplate redis, MqProperties props,
                                                       BusinessMetrics metrics,
                                                       org.springframework.core.env.Environment env) {
        String producerName = env.getProperty("spring.application.name", "");
        return new TransactionalProducer(redis, props, metrics, producerName);
    }

    @Bean
    @ConditionalOnMissingBean
    public HalfMessageScanner halfMessageScanner(TransactionalProducer producer, MqProperties props,
                                                 BusinessMetrics metrics,
                                                 org.springframework.beans.factory.ObjectProvider<TransactionChecker> checkers) {
        List<TransactionChecker> list = checkers.orderedStream().toList();
        return new HalfMessageScanner(producer, props, metrics, list);
    }
}
