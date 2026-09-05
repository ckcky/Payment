package com.payment.payment.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.LedgerPostingGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 出站 RPC 客户端装配。
 *
 * <p>Feature 016（ADR-0054）职责归位：payment 退回能力提供方——<b>不再持有</b>
 * {@code FulfillmentGateway}（履约由 order 层驱动）与 {@code AutoRefundGateway}
 * （自动退款由 order transaction 层发起，经 {@code /internal/payments/refund-command} 回调执行）；
 * 订单回写 {@code OrderGateway} 由 {@code OrderFeignClient} 直接提供。
 * 仅保留记账出站网关（Feature 004 / ADR-0009）。
 * Feign 层面的连接/读取超时与熔断见 {@code application.yml} 的 {@code spring.cloud.openfeign} 配置。</p>
 */
@Configuration
public class PaymentClientConfig {

    /** 记账出站网关：记账失败不回滚支付成功事实（ADR-0009，Saga + 幂等，禁 2PC/XA）。 */
    @Bean
    public LedgerPostingGateway ledgerPostingGateway(LedgerFeignClient ledgerClient,
                                                     BusinessMetrics metrics) {
        return new FeignLedgerPostingGateway(ledgerClient, metrics);
    }
}
