package com.payment.settlement.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * settlement-service → merchant-service 的 Feign 客户端（原始出站契约）。
 */
@FeignClient(name = "merchant-service", url = "${services.merchant.url:http://localhost:8081}",
        configuration = SettlementFeignConfig.class)
public interface MerchantFeignClient {

    @GetMapping("/merchants/{id}")
    MerchantDto getMerchant(@PathVariable("id") Long id);
}
