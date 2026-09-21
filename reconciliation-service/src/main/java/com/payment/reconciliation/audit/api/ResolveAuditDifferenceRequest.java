package com.payment.reconciliation.audit.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 审计差异人工收口请求（spec 032 §7.2 / T22，F7 关闭路径）：备注必填（ADR-0019 纪律）。
 * CROSS_LEDGER_MISMATCH 等调账后 recheck 无法转绿的差异，由人工确认直达 RESOLVED 后关批。
 */
public record ResolveAuditDifferenceRequest(@NotBlank String resolutionNote,
                                            String resolvedBy,
                                            String resolvedAt) {
}
