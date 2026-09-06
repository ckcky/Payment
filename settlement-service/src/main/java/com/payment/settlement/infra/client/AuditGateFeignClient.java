package com.payment.settlement.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * settlement-service → reconciliation-service 的 Feign 客户端：审计结算门禁
 * （spec 017 / ADR-0065，端点契约见 plan §10.3）。
 *
 * <p>独立 contextId，避免与既有 {@code ReconciliationFeignClient} 冲突。</p>
 */
@FeignClient(name = "reconciliation-service", contextId = "auditGateClient",
        configuration = SettlementFeignConfig.class)
public interface AuditGateFeignClient {

    @GetMapping("/internal/audit/settlement-gate")
    AuditGateDto getSettlementGate(@RequestParam("period") String period);

    /**
     * 门禁响应 DTO（camelCase 契约）。
     */
    record AuditGateDto(String decision, boolean balanced, List<BlockingDifferenceDto> blockingDifferences) {
    }

    record BlockingDifferenceDto(String kind, String sourceType, String sourceId,
                                 String severity, long amountMinor, String currency) {
    }
}
