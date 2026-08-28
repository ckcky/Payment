package com.payment.payment.application;

import com.payment.common.dto.rpc.PaymentSucceededRequest;

/** 支付成功时回写订单/交易的出站同步 RPC 端口；生产用 Feign 实现，测试用 fake。 */
public interface OrderGateway {

    void notifyPaymentSucceeded(PaymentSucceededRequest request);
}
