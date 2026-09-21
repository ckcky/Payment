package com.payment.reconciliation.audit.infra;

import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.reconciliation.infra.client.FactsClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * reconciliation-service → ledger-service 的 Feign 客户端（spec 017 审计只读 + 调账记账，
 * 031 事件化契约：复用 {@link AccountingEventRequest} / {@link AccountingEventResponse}）。
 */
@FeignClient(name = "ledger-service", contextId = "ledgerAuditClient",
        configuration = FactsClientConfig.class)
public interface LedgerAuditFeignClient {

    /** 全部账本交易与分录（账证 / 账账核对输入，含账户语义回显）。 */
    @GetMapping("/internal/ledger/postings/all")
    List<AccountingEventResponse> allPostings();

    /** 借贷平衡（FR-005 / 结算门禁硬条件）。 */
    @GetMapping("/internal/ledger/balance")
    BalanceDto balance();

    /** 调账记账（ADJUSTMENT 事件，sourceType=RECONCILIATION，sourceId=adjustNo）。 */
    @PostMapping("/internal/ledger/accounting-events")
    AccountingEventResponse postEvent(@RequestBody AccountingEventRequest request);

    record BalanceDto(boolean balanced, Map<String, Long> diffByCurrency) {
    }
}
