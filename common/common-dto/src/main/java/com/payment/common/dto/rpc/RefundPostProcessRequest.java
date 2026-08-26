package com.payment.common.dto.rpc;

/**
 * 退款成功后的履约/权益后处理 RPC 请求（refund-service → entitlement-service）。
 *
 * <p>退款成功后，refund-service 请求下游撤销对应权益；下游按自身规则处理，
 * refund-service 不直接修改 entitlement 内部状态。</p>
 */
public record RefundPostProcessRequest(Long refundId, Long paymentId, String orderId,
                                       String userId, String reason) {
}
