package com.payment.reconciliation.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * reconciliation-service → refund-service 的 Feign 适配器（已确认退款事实查询）。
 */
@FeignClient(name = "refund-service", url = "${services.refund.url:http://localhost:8085}")
public interface RefundFactsFeignClient {

    @GetMapping("/internal/refunds/confirmed-facts")
    List<RefundFactDto> fetchConfirmedFacts();
}
