package com.payment.reconciliation.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 差异人工收口请求（spec §10.1，按 RD 单号）：备注必填（ADR-0019）。
 */
public record ResolveByNoRequest(@NotBlank String resolutionNote, String resolvedBy, String resolvedAt) {
}
