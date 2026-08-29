package com.payment.refund.application;

import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;

/**
 * refund-service → fulfillment-service 的出站同步 RPC 端口：退款确认后请求撤销履约。
 * 生产用 Feign 实现，测试用 fake。
 */
public interface FulfillmentGateway {

    RefundFulfillmentResponse notifyRefund(RefundFulfillmentRequest request);
}
