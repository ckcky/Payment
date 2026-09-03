package com.payment.settlement.infra.client;

import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ledger-service 的 Feign 客户端（内部记账端点，复用 payment-service 既有契约）。
 */
@FeignClient(name = "ledger-service"
        configuration = LedgerFeignConfig.class)
public interface LedgerFeignClient {

    @PostMapping("/internal/ledger/postings")
    PostingResponse post(@RequestBody PostingRequest request);

    @GetMapping("/internal/ledger/postings")
    PostingResponse find(@RequestParam("idempotencyKey") String idempotencyKey);
}
