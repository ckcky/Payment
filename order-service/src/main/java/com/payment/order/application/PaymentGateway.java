package com.payment.order.application;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;

/**
 * 订单创建后创建支付意图的出站同步 RPC 端口；生产用 Feign，测试用 fake。
 */
public interface PaymentGateway {

    CreatePaymentResponse createPayment(CreatePaymentRequest request);
}
