package com.payment.reconciliation.audit.infra;

import com.payment.reconciliation.infra.client.FactsClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * reconciliation-service → settlement-service 的 Feign 客户端（spec 017 跨账 / 账证核对输入）。
 */
@FeignClient(name = "settlement-service", contextId = "settlementAuditClient",
        configuration = FactsClientConfig.class)
public interface SettlementAuditFeignClient {

    @GetMapping("/internal/settlements/audit-facts")
    List<SettlementFactDto> auditFacts(@RequestParam("period") String period);

    /** 镜像 settlement 侧 SettlementAuditFactView；id 为 ledger 侧跨账核对键。 */
    record SettlementFactDto(Long id, String batchNo, String merchantId, String period, String currencyCode,
                             long incomeMinor, long refundMinor, long adjustmentMinor,
                             long netMinor, String status) {
    }
}
