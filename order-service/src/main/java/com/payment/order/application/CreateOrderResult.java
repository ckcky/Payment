package com.payment.order.application;

import com.payment.order.domain.OrderStatus;

/**
 * 创建订单结果：订单 + 其 1:1 交易 + 同步创建的支付意图。
 *
 * <p>{@code payUrl}（ADR-0048 修订版）：payment 侧 mock-cashier.enabled=true 时返回的
 * 收银台跳转链接，此处原样透传（order 不解析、不改写）；默认路径为 {@code null}。</p>
 */
public record CreateOrderResult(Long orderId, Long transactionId, OrderStatus status,
                                long totalMinor, String currencyCode, Long paymentId,
                                String paymentStatus, String payUrl) {

    /** 兼容构造（无收银台路径）：payUrl 为 null。 */
    public CreateOrderResult(Long orderId, Long transactionId, OrderStatus status,
                             long totalMinor, String currencyCode, Long paymentId,
                             String paymentStatus) {
        this(orderId, transactionId, status, totalMinor, currencyCode, paymentId, paymentStatus, null);
    }
}
