package com.payment.common.dto.rpc;

/**
 * 退款后处理 RPC 的响应（entitlement-service → payment-service（refund 包））。
 *
 * <p>{@code status} 为后处理结果枚举名（如 REVOKED / NOOP / FAILED）；退款侧记录
 * 该结果但不反写退款成功事实（退款成功事实不可回退）。</p>
 */
public record RefundPostProcessResponse(String refundNo, String status) {
}
