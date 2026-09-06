package com.payment.reconciliation.audit.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 调账请求（FR-015 / FR-016）：kind（SUPPLEMENT/REVERSE/CORRECT/TRANSFER/WRITE_OFF）、
 * 金额（分）、operator + reason 必填；reviewer 软约束（plan §11 ⑥）。
 */
public record AdjustDifferenceRequest(@NotBlank String kind, @Positive long amountMinor,
                                      String targetAccountCode,
                                      @NotBlank String operator, String reviewer,
                                      @NotBlank String reason) {
}
