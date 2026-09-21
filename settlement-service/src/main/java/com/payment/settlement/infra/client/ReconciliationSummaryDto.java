package com.payment.settlement.infra.client;

import java.util.List;

/**
 * reconciliation-service 返回的结算汇总 DTO。
 *
 * <p>032/G4：{@code facts} = 全部已确认事实；{@code excludedFacts} = 未收口差异净影响扣减项。</p>
 */
public record ReconciliationSummaryDto(String period, List<SettlementFactDto> facts,
                                       List<ExcludedFactDto> excludedFacts,
                                       int unresolvedDifferenceCount) {

    /** 兼容构造（032 前的 3 字段形态）：excludedFacts 缺省为空列表。 */
    public ReconciliationSummaryDto(String period, List<SettlementFactDto> facts, int unresolvedDifferenceCount) {
        this(period, facts, List.of(), unresolvedDifferenceCount);
    }
}
