package com.payment.payment.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.FulfillmentGateway;
import com.payment.payment.application.LedgerPostingGateway;
import com.payment.payment.application.OrderGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 出站 RPC 客户端装配：把 Feign 实现的 {@link FulfillmentFeignClient} 用弹性装饰器包裹，
 * 对外暴露为 {@code @Primary} 的 {@link FulfillmentGateway}，使应用层注入的即为带重试/退避的实例；
 * 订单回写经 {@link FeignOrderGateway} 翻译 409 ORDER_NOT_PAYABLE 为 typed 异常（Feature 015 / C5）；
 * 同时装配 {@link LedgerPostingGateway}（Feature 004 / ADR-0009）。
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

    /** 订单回写网关：409 ORDER_NOT_PAYABLE → OrderNotPayableException（触发自动退款），其余原样上抛。 */
    @Bean
    @Primary
    public OrderGateway orderGateway(OrderFeignClient feignClient) {
        return new FeignOrderGateway(feignClient);
    }

    /** 记账出站网关：记账失败不回滚支付成功事实（ADR-0009，Saga + 幂等，禁 2PC/XA）。 */
    @Bean
    public LedgerPostingGateway ledgerPostingGateway(LedgerFeignClient ledgerClient,
                                                     BusinessMetrics metrics) {
        return new FeignLedgerPostingGateway(ledgerClient, metrics);
    }
}
