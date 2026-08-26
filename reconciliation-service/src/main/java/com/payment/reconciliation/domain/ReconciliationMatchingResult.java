package com.payment.reconciliation.domain;

import java.util.List;

/**
 * 对账匹配结果：一致匹配列表 + 差异列表。
 */
public record ReconciliationMatchingResult(List<Match> matches, List<Difference> differences) {
}
