package com.payment.reconciliation.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * reconciliation-service → payment-service 的 Feign 适配器（已确认支付事实查询）。
 */
@FeignClient(name = "payment-service", contextId = "paymentFactsClient",
        configuration = FactsClientConfig.class)
public interface PaymentFactsFeignClient {

    @GetMapping("/internal/payments/confirmed-facts")
    List<PaymentFactDto> fetchConfirmedFacts();
}
