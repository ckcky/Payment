package com.payment.refund.application;

import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;

/**
 * refund-service → payment-service 的出站同步 RPC 端口：查询支付金额、执行渠道退款尝试。
 * 生产用 Feign 实现，测试用 fake。
 */
public interface PaymentRefundGateway {

    PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request);

    RefundAttemptResponse attemptRefund(RefundAttemptRequest request);
}
