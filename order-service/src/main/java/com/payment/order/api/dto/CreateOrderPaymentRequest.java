package com.payment.order.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 显式选渠道创建支付单的请求体（Feature 015，INV-2）：同一订单可多次提交以新建不同渠道的支付单。
 */
public record CreateOrderPaymentRequest(@NotBlank String channelCode) {
}
