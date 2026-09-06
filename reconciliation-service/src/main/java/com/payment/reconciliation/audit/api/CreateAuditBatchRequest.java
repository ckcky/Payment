package com.payment.reconciliation.audit.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建审计批次请求（FR-021）：period（如 2026-08-31）、scope（CERTIFICATE/LEDGER/REAL/REPORT/ALL）。
 */
public record CreateAuditBatchRequest(@NotBlank String period, @NotBlank String scope, String triggeredBy) {
}
