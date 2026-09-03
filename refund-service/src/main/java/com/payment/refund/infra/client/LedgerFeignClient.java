package com.payment.refund.infra.client;

import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ledger-service 的 Feign 客户端（内部记账端点，FR-010 同步 RPC + 幂等）。
 */
@FeignClient(name = "ledger-service")
public interface LedgerFeignClient {

    @PostMapping("/internal/ledger/postings")
    PostingResponse post(@RequestBody PostingRequest request);

    @GetMapping("/internal/ledger/postings")
    PostingResponse find(@RequestParam("idempotencyKey") String idempotencyKey);
}
