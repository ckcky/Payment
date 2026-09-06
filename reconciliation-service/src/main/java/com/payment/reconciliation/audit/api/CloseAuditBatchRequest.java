package com.payment.reconciliation.audit.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 关批请求（FR-018）。
 */
public record CloseAuditBatchRequest(@NotBlank String operator) {
}
