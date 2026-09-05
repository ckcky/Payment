package com.payment.common.dto.rpc;

/**
 * 执行渠道退款的 RPC 请求（payment-service 内 refund 包 → payment 包，同进程经 Feign 面）。
 *
 * <p>携带退款决策所需事实；payment 包只执行渠道退款尝试并返回结果，
 * 不决定退款整体状态（退款决策归属 refund 包）。</p>
 *
 * <p>ADR-0063：跨服务/跨包标识一律业务单号 {@code refundNo}，数值 refund_id 不出边界。</p>
 */
public record RefundAttemptRequest(String refundNo, String paymentNo, String orderNo, String userId,
                                   long amountMinor, String currencyCode, String reason,
                                   String idempotencyKey) {
}
