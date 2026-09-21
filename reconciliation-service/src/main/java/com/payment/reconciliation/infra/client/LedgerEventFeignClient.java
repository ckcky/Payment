package com.payment.reconciliation.infra.client;

import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * reconciliation-service → ledger-service 的资金事实事件客户端（spec 032 §10.2）：
 * 复用 031 事件契约端点，仅覆盖 CHANNEL_SETTLEMENT / CHANNEL_FEE 两类事件的上报。
 */
@FeignClient(name = "ledger-service", contextId = "ledgerFundEventClient",
        configuration = FactsClientConfig.class)
public interface LedgerEventFeignClient {

    @PostMapping("/internal/ledger/accounting-events")
    AccountingEventResponse postEvent(@RequestBody AccountingEventRequest request);
}
