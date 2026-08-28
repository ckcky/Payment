package com.payment.common.dto.rpc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 创建支付意图的跨服务 RPC 请求（order-service → payment-service）。
 *
 * <p>金额为最小货币单位（long）。幂等键由调用方提供，payment-service 据此保证重复请求不产生
 * 第二次资金动作。字段约束在入口即被 {@code @Valid} 校验，挡住非法资金入参
 * （负数/零金额、非法币种、空订单/幂等键）。</p>
 */
public record CreatePaymentRequest(
        @NotBlank String orderId,
        @NotBlank String transactionId,
        @NotBlank String userId,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currencyCode must be ISO-4217 (e.g. CNY)")
        String currencyCode,
        @NotBlank String idempotencyKey,
        @NotBlank String channelCode) {
}
