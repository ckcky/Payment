package com.payment.reconciliation.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 处理差异请求：指定差异引用、处理说明、操作人与处理时间（ADR-0019）。
 * resolvedAt 为空时由服务端取当前时间（ISO-8601）。
 *
 * <p>{@code resolutionNote} 是资金运营的处理依据，MUST NOT 为空（spec 006 T028 / FR-010）：
 * 在 API 边界用 {@link NotBlank} 拦截；领域层 {@code Difference.resolve} 另有同样的非空校验
 * 作为纵深防御——任一层生效都能挡住「无依据处理」。</p>
 *
 * <p>操作人由既有 {@code resolvedBy} 承载（语义即「谁处理的」），不新增重复字段。</p>
 */
public record ResolveDifferenceRequest(
        String reference,

        @NotBlank(message = "resolutionNote must not be blank")
        String resolutionNote,

        String resolvedBy,

        String resolvedAt) {
}
