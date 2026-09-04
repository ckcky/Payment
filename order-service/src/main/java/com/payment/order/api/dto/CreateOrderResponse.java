package com.payment.order.api.dto;

import com.payment.order.application.CreateOrderResult;

/**
 * 创建订单响应：订单 + 其 1:1 交易 + 支付意图。
 *
 * <p>{@code payUrl}（ADR-0048 修订版）：mock 收银台跳转链接，仅在 payment 侧
 * mock-cashier.enabled=true 时非空；演示控制台以 {@code window.open(payUrl)} 打开收银台。
 * 默认同步 charge 主链下为 {@code null}，既有调用方零影响。</p>
 */
public record CreateOrderResponse(Long orderId, String orderNo, Long transactionId, String transactionNo,
                                  String status, long totalMinor, String currencyCode, Long paymentId,
                                  String paymentStatus, String payUrl) {

    /** 兼容构造（无收银台路径）：payUrl 为 null。 */
    public CreateOrderResponse(Long orderId, String orderNo, Long transactionId, String transactionNo,
                               String status, long totalMinor, String currencyCode, Long paymentId,
                               String paymentStatus) {
        this(orderId, orderNo, transactionId, transactionNo, status, totalMinor, currencyCode,
                paymentId, paymentStatus, null);
    }

    public static CreateOrderResponse from(CreateOrderResult result) {
        return new CreateOrderResponse(result.orderId(), result.orderNo(), result.transactionId(),
                result.transactionNo(), result.status().name(), result.totalMinor(), result.currencyCode(),
                result.paymentId(), result.paymentStatus(), result.payUrl());
    }
}
