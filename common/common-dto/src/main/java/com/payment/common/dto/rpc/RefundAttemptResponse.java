package com.payment.common.dto.rpc;

/**
 * 渠道退款尝试的跨服务 RPC 响应（payment-service → refund-service）。
 *
 * <p>{@code status} 为退款尝试结果枚举名（SUCCEEDED / FAILED / UNKNOWN）；UNKNOWN 表示
 * 渠道结果未确认，refund-service 不得当作成功或失败，需等待查询/回调收敛。</p>
 */
public record RefundAttemptResponse(Long refundId, String status, String channelReference) {
}
