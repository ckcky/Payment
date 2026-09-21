package com.payment.reconciliation.api;

import java.util.List;

/**
 * 结算汇总响应：某周期的匹配事实列表 + 未收口差异扣减项 + 未处理差异数。
 *
 * <p>032/G4（C-13）：口径改为「全部已确认事实 − 未收口差异净影响」——{@code facts}
 * 含该周期全部已确认事实（不再静默剔除未匹配事实），未证实部分经
 * {@code excludedFacts} 显式扣减（含原因），杜绝静默漏结算。</p>
 */
public record ReconciliationSettlementSummaryResponse(String period,
                                                      List<ReconciliationSettlementFact> facts,
                                                      List<ReconciliationExcludedFact> excludedFacts,
                                                      int unresolvedDifferenceCount) {

    /** 兼容构造（032 前的 3 字段形态）：excludedFacts 缺省为空列表。 */
    public ReconciliationSettlementSummaryResponse(String period, List<ReconciliationSettlementFact> facts,
                                                   int unresolvedDifferenceCount) {
        this(period, facts, List.of(), unresolvedDifferenceCount);
    }
}
