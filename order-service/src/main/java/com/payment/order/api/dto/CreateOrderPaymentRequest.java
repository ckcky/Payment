package com.payment.order.api.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 创建支付单的请求体（Feature 015 + Feature 028）。
 *
 * <p><b>channelCode 语义（FR-026）</b>：
 * <ul>
 *   <li><b>传值</b>——显式指定渠道（保 INV-2「换渠道 = 调用方新建支付单」），后端不做覆盖。</li>
 *   <li><b>不传 / 空</b>——只表达「支付意图」，由 payment-service 的 {@code ChannelRouter}
 *       按 {@code enabled + priority} 确定性选路（ADR-0073 / US2）。</li>
 * </ul>
 *
 * <p>注意此处<b>不再有 {@code @NotBlank}</b>（spec 028 / FR-026）：留空是合法输入。
 * 但仍用 {@link Pattern} 约束非空时的形态，避免把空白串当渠道码透传下去
 * （空白串交由 {@code treatBlankAsNull} 归一为 null，见 {@code OrderController}）。
 */
public record CreateOrderPaymentRequest(
        @Pattern(regexp = "\\s*|^[A-Za-z][A-Za-z0-9_]*$",
                message = "channelCode 若提供须为字母开头的渠道码（如 ALIPAY / WECHAT）")
        String channelCode) {
}
