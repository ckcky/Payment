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
 */
public record CreatePaymentRequest(
        @NotBlank String orderNo,
        @NotBlank String transactionId,
        @NotBlank String userId,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currencyCode must be ISO-4217 (e.g. CNY)")
        String currencyCode,
        String idempotencyKey,
        @NotBlank String channelCode) {
}
