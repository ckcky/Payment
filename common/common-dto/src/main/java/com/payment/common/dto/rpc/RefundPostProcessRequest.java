package com.payment.common.dto.rpc;

/**
 * 退款成功后的履约/权益后处理 RPC 请求（payment-service（refund 包）→ entitlement-service）。
 *
 * <p>退款成功后，退款侧请求下游撤销对应权益；下游按自身规则处理，
 * 退款侧不直接修改 entitlement 内部状态。</p>
 */
public record RefundPostProcessRequest(String refundNo, String paymentNo, String orderNo,
                                       String userId, String reason) {
}
