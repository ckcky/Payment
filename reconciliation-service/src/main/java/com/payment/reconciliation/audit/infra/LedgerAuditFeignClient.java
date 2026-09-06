package com.payment.reconciliation.audit.infra;

import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.reconciliation.infra.client.FactsClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * reconciliation-service → ledger-service 的 Feign 客户端（spec 017 审计只读 + 调账记账）。
 * 复用 {@link PostingRequest} / {@link PostingResponse} 通用契约（common-dto）。
 */
@FeignClient(name = "ledger-service", contextId = "ledgerAuditClient",
        configuration = FactsClientConfig.class)
public interface LedgerAuditFeignClient {

    /** 全部分录（账证 / 账账核对输入）。 */
    @GetMapping("/internal/ledger/postings/all")
    List<PostingResponse> allPostings();

    /** 借贷平衡（FR-005 / 结算门禁硬条件）。 */
    @GetMapping("/internal/ledger/balance")
    BalanceDto balance();

    /** 调账记账（ADJUSTMENT 来源，幂等键 adjust:{adjustNo}）。 */
    @PostMapping("/internal/ledger/postings")
    PostingResponse post(@RequestBody PostingRequest request);

    record BalanceDto(boolean balanced, Map<String, Long> diffByCurrency) {
    }
}
