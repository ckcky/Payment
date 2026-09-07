package com.payment.payment.application;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundResultNotification;

/**
 * 回写订单/交易的出站同步 RPC 端口；生产用 Feign 实现，测试用 fake。
 *
 * <p>spec 019（ADR-0067）：新增 {@link #notifyRefundResult(RefundResultNotification)}——
 * 退款终态收敛后以 TXRF+PMRF 双号通知 order 收口（transaction 终态 + refunded_minor 累加 +
 * 订单状态流转 + 秒杀回补 + 履约终止）。</p>
 */
public interface OrderGateway {

    void notifyPaymentSucceeded(PaymentSucceededRequest request);

    /** 退款终态通知（SUCCEEDED / FAILED；重复通知由 order 终态吸收，幂等）。 */
    void notifyRefundResult(RefundResultNotification notification);
}
