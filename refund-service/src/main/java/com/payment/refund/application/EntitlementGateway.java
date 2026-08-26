package com.payment.refund.application;

import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;

/**
 * refund-service → entitlement-service 的出站同步 RPC 端口：退款成功后请求权益吊销。
 * 生产用 Feign 实现，测试用 fake。
 */
public interface EntitlementGateway {

    RefundPostProcessResponse notifyRefundPostProcess(RefundPostProcessRequest request);
}
