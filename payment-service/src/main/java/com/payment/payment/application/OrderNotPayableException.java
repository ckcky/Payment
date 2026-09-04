package com.payment.payment.application;

import com.payment.payment.domain.PaymentStatus;

/**
 * 订单侧明确拒绝支付成功回写（HTTP 409 {@code ORDER_NOT_PAYABLE}，Feature 015 / C5 / INV-1）：
 * 订单已取消/超时/关闭，本笔支付单不应继续以 SUCCEEDED 收场。
 *
 * <p>由 {@code PaymentResultProcessor} 捕获并触发自动退款（ADR-015 / P4）：
 * 修复「订单非待支付态的 markPaid 异常被 catch(RuntimeException ignored) 静默吞掉」的历史问题——
 * 付款成功的钱必须原路退回，而不是悬在 SUCCEEDED。</p>
 */
public class OrderNotPayableException extends RuntimeException {

    private final String paymentNo;
    private final String orderNo;

    public OrderNotPayableException(String paymentNo, String orderNo, String orderStatus) {
        super("order not payable: " + orderNo + " status=" + orderStatus
                + " (payment " + paymentNo + " succeeded on non-payable order)");
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
    }

    /** 触发自动退款的支付单号。 */
    public String getPaymentNo() {
        return paymentNo;
    }

    /** 已不可支付的订单号。 */
    public String getOrderNo() {
        return orderNo;
    }

    /** 支付单语义上应为 {@link PaymentStatus#SUCCEEDED}：钱已收下、订单不认，必须退回。 */
    public PaymentStatus expectedPaymentStatus() {
        return PaymentStatus.SUCCEEDED;
    }
}
