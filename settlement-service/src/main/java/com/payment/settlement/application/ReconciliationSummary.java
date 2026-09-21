package com.payment.settlement.application;

import java.util.List;

/**
 * 对账结算汇总（本地端口值对象）：某周期的确认财务事实、未收口差异扣减项与未解决差异数。
 *
 * <p>032/G4（C-13）：口径 = 「全部已确认事实 − 未收口差异净影响」。{@code facts} 含该周期
 * 全部已确认事实；未证实部分经 {@code excludedFacts} 显式扣减（含原因），杜绝静默漏结算。</p>
 */
public record ReconciliationSummary(String period, List<SettlementFact> facts,
                                    List<ExcludedSettlementFact> excludedFacts,
                                    int unresolvedDifferenceCount) {

    /** 兼容构造（032 前的 3 字段形态）：excludedFacts 缺省为空列表。 */
    public ReconciliationSummary(String period, List<SettlementFact> facts, int unresolvedDifferenceCount) {
        this(period, facts, List.of(), unresolvedDifferenceCount);
    }
}
