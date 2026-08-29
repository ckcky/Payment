package com.payment.settlement.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.settlement.application.LedgerPostingGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 出站 RPC 客户端装配：把 Feign 实现的 {@link LedgerFeignClient} 用 {@link FeignLedgerPostingGateway} 包裹，
 * 对外暴露为 {@link LedgerPostingGateway}（ADR-0023，复用 payment-service 既有模式）。
 * Feign 层面的连接/读取超时与弹性配置见 {@code SettlementFeignConfig} / {@code LedgerFeignConfig}。
 */
@Configuration
public class SettlementClientConfig {

    /** 记账出站网关：记账失败不回滚结算成功事实（ADR-0023，Saga + 幂等，禁 2PC/XA）。 */
    @Bean
    public LedgerPostingGateway ledgerPostingGateway(LedgerFeignClient ledgerClient,
                                                     BusinessMetrics metrics) {
        return new FeignLedgerPostingGateway(ledgerClient, metrics);
    }
}
