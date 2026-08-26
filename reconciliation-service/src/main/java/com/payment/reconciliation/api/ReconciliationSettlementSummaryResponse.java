package com.payment.reconciliation.api;

import java.util.List;

/**
 * 结算汇总响应：某周期的匹配事实列表 + 未处理差异数。
 */
public record ReconciliationSettlementSummaryResponse(String period,
                                                      List<ReconciliationSettlementFact> facts,
                                                      int unresolvedDifferenceCount) {
}
