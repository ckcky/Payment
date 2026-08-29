package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.settlement.application.ReconciliationClient;
import com.payment.settlement.application.ReconciliationSummary;
import com.payment.settlement.application.SettlementFact;
import feign.FeignException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ReconciliationClient 的 Feign 适配器：把 reconciliation-service DTO 映射为领域视图。
 * reconciliation 返回 404（该周期无对账批次）⇒ 归一化为 {@code NOT_FOUND}（N2，「无对账 ⇒ 不可结算」）。
 */
@Component
public class FeignReconciliationClient implements ReconciliationClient {

    private final ReconciliationFeignClient feign;

    public FeignReconciliationClient(ReconciliationFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public ReconciliationSummary getSettlementSummary(String period) {
        try {
            ReconciliationSummaryDto dto = feign.getSettlementSummary(period);
            List<SettlementFact> facts = dto.facts() == null ? List.of() : dto.facts().stream()
                    .map(f -> new SettlementFact(f.reference(), f.type(), f.amountMinor(), f.currencyCode()))
                    .toList();
            return new ReconciliationSummary(dto.period(), facts, dto.unresolvedDifferenceCount());
        } catch (FeignException.NotFound e) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "reconciliation not found for period: " + period);
        }
    }
}
