package com.payment.payment.application;

/**
 * 自动退款出站端口（Feature 015 / P4 / INV-1）：
 * 订单侧 409 {@code ORDER_NOT_PAYABLE}（订单已取消/超时/关闭或已被其他支付单支付）时，
 * 把多收的钱原路退回。支付单保持 SUCCEEDED 事实不回滚（钱确实收下过）。
 */
public interface AutoRefundGateway {

    /**
     * 发起自动退款（同步，带重试退避）。
     *
     * @param paymentNo 待退款的支付单号（SUCCEEDED）
     * @param cause     触发原因（订单拒绝详情，用于审计与转人工日志）
     */
    void autoRefund(String paymentNo, OrderNotPayableException cause);
}
