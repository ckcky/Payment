package com.payment.common.dto.rpc;

/**
 * 执行渠道退款的 RPC 请求（payment-service 内 refund 包 → payment 包，同进程经 Feign 面）。
 *
 * <p>携带退款决策所需事实；payment 包只执行渠道退款尝试并返回结果，
 * 不决定退款整体状态（退款决策归属 refund 包）。</p>
 *
 * <p>ADR-0063：跨服务/跨包标识一律业务单号 {@code refundNo}，数值 refund_id 不出边界。</p>
 *
 * <p>Feature 016（FR-005 / ADR-0054）：新增 {@code transactionNo} —— 自动退款由 order
 * transaction 层以 {@code transactionNo + paymentNo} 发起，本请求 MUST 携带业务上下文；
 * 手工退款路径（无交易上下文）允许为 {@code null}。</p>
 */
public record RefundAttemptRequest(String refundNo, String transactionNo, String paymentNo, String orderNo,
                                   String userId, long amountMinor, String currencyCode, String reason,
                                   String idempotencyKey) {

    /** 兼容构造（无交易上下文）：transactionNo 为 null（手工退款路径）。 */
    public RefundAttemptRequest(String refundNo, String paymentNo, String orderNo, String userId,
                                long amountMinor, String currencyCode, String reason, String idempotencyKey) {
        this(refundNo, null, paymentNo, orderNo, userId, amountMinor, currencyCode, reason, idempotencyKey);
    }
}
