package com.payment.order.application;

import com.payment.order.domain.OrderStatus;

/**
 * 下单应用层结果（ADR-0063）：对外只暴露业务单号（orderNo/transactionNo/paymentNo），
 * 数值主键仅本服务内部使用。
 */
public record CreateOrderResult(String orderNo, String transactionNo,
                                OrderStatus status, long totalMinor, String currencyCode, String paymentNo,
                                String paymentStatus, String payUrl) {
    /** 兼容构造（无收银台路径）：payUrl 为 null。 */
    public CreateOrderResult(String orderNo, String transactionNo,
                             OrderStatus status, long totalMinor, String currencyCode, String paymentNo,
                             String paymentStatus) {
        this(orderNo, transactionNo, status, totalMinor, currencyCode, paymentNo, paymentStatus, null);
    }
}
