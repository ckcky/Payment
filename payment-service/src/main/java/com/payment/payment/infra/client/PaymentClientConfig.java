package com.payment.payment.infra.client;

import com.payment.payment.application.FulfillmentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 出站 RPC 客户端装配：把 Feign 实现的 {@link FulfillmentFeignClient} 用弹性装饰器包裹，
 * 对外暴露为 {@code @Primary} 的 {@link FulfillmentGateway}，使应用层注入的即为带重试/退避的实例。
 * Feign 层面的连接/读取超时与熔断见 {@code application.yml} 的 {@code spring.cloud.openfeign} 配置。
 */
@Configuration
public class PaymentClientConfig {

    @Bean
    @Primary
    public FulfillmentGateway fulfillmentGateway(FulfillmentFeignClient feignClient,
                                                @Value("${payment.fulfillment.retry.max-attempts:3}") int maxAttempts,
                                                @Value("${payment.fulfillment.retry.backoff-ms:200}") long backoffMillis) {
        return new ResilientFulfillmentGateway(feignClient, maxAttempts, backoffMillis);
    }
}
