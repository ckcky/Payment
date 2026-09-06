package com.payment.reconciliation.audit.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 挂账请求（FR-014）：operator + reason 必填（NFR-004 留痕）。
 */
public record SuspendDifferenceRequest(@NotBlank String operator, @NotBlank String reason) {
}
