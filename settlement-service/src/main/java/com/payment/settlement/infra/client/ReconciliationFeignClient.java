package com.payment.settlement.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * settlement-service → reconciliation-service 的 Feign 客户端（原始出站契约）。
 */
@FeignClient(name = "reconciliation-service", url = "${services.reconciliation.url:http://localhost:8088}",
        configuration = SettlementFeignConfig.class)
public interface ReconciliationFeignClient {

    @GetMapping("/internal/reconciliation/settlement-summary")
    ReconciliationSummaryDto getSettlementSummary(@RequestParam("period") String period);
}
