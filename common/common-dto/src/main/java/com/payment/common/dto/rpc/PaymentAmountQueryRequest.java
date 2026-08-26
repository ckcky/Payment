package com.payment.common.dto.rpc;

/**
 * 查询支付金额的跨服务 RPC 请求（refund-service → payment-service）。
 *
 * <p>退款受理前，refund-service 需要确认原始支付金额与币种以计算可退款金额；
 * 只查询事实，不修改 payment 内部状态。</p>
 */
public record PaymentAmountQueryRequest(Long paymentId) {
}
