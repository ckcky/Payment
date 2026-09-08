package com.payment.order.api.dto;

import com.payment.order.domain.Order;
import java.util.List;

/**
 * 订单详情响应。
 *
 * <p>paymentNo 为订单当前生效支付单（ADR-0063 跨系统引用用业务单号）：
 * 换渠道重付会随最新一张支付单前移，消费方（演示页 / E2E）据此跟随生效支付，
 * 避免展示或退款落在已弃单的 UNKNOWN 旧支付单上。</p>
 */
public record OrderResponse(Long id, String orderNo, String userId, String merchantId, String status,
                            long totalMinor, String currencyCode, long paidMinor, long refundedMinor,
                            String paymentNo, List<OrderItemResponse> items) {

    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(OrderItemResponse::from).toList();
        return new OrderResponse(order.getId(), order.getOrderNo(), order.getUserId(), order.getMerchantId(),
                order.getStatus().name(), order.getTotalMinor(), order.getCurrencyCode(),
                order.getPaidMinor(), order.getRefundedMinor(), order.getPaymentNo(), items);
    }
}
