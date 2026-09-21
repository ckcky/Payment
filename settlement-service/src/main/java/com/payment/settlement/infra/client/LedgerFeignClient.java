package com.payment.settlement.infra.client;

import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ledger-service 的 Feign 客户端（spec 031 事件化契约，复用 payment-service 既有模式）。
 */
@FeignClient(name = "ledger-service",
        configuration = LedgerFeignConfig.class)
public interface LedgerFeignClient {

    @PostMapping("/internal/ledger/accounting-events")
    AccountingEventResponse postEvent(@RequestBody AccountingEventRequest request);

    /** 按事件回查（补偿核对入口，spec 031 §12）。 */
    @GetMapping("/internal/ledger/postings")
    AccountingEventResponse find(@RequestParam("eventType") String eventType,
                                 @RequestParam("sourceId") String sourceId);
}
