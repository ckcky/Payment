package com.payment.common.dto.rpc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 创建支付意图的跨服务 RPC 请求（order-service → payment-service）。
 *
 * <p>金额为最小货币单位（long）。跨系统关联一律用业务单号（ADR-0063）：
 * {@code orderNo} / {@code transactionId} 分别承载订单号与交易单号。</p>
 *
 * <p>Feature 015：幂等键改由 payment-service 服务端生成
 * （{@code payment:{orderNo}:{channelCode}:{attemptSeq}}，修复第二笔支付撞同一幂等键
 * 静默少记账），调用方传 {@code null} 即可，故不再标注 {@code @NotBlank}。</p>
 *
 * <p><b>Feature 028（FR-025）</b>：{@code channelCode} 由必填改为<b>可选</b>——
 * 传值 = 显式指定渠道（不选路，保 INV-2）；传 {@code null}/空 = 只表达支付意图，
 * 由 payment-service 的 {@code ChannelRouter} 按 {@code enabled + priority} 确定性选路
 * （ADR-0073）。非空时仍用 {@link Pattern} 约束形态，坏值在入参校验期即被拒
 * （避免空白串被当作渠道码进入 Router）。</p>
 */
public record CreatePaymentRequest(
        @NotBlank String orderNo,
        @NotBlank String transactionId,
        @NotBlank String userId,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currencyCode must be ISO-4217 (e.g. CNY)")
        String currencyCode,
        String idempotencyKey,
        @Pattern(regexp = "\\s*|^[A-Za-z][A-Za-z0-9_]*$",
                message = "channelCode 若提供须为字母开头的渠道码（如 ALIPAY / WECHAT）")
        String channelCode,
        /** spec 031（§13，H11 前置收编）：订单携带商户号——PAYMENT_CAPTURE 事件解析商户应付账户之必需。 */
        String merchantId) {
}
