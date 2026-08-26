package com.payment.settlement.application;

import java.util.List;

/**
 * 对账结算汇总（本地端口值对象）：某周期的确认财务事实与未解决差异数。
 */
public record ReconciliationSummary(String period, List<SettlementFact> facts, int unresolvedDifferenceCount) {
}
