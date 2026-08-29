package com.payment.common.dto.rpc;

/**
 * 渠道退款尝试的跨服务 RPC 响应（payment-service → refund-service）。
 *
 * <p>{@code status} 为退款尝试结果枚举名（SUCCEEDED / FAILED / UNKNOWN）；UNKNOWN 表示
 * 渠道结果未确认，refund-service 不得当作成功或失败，需等待查询/回调收敛。</p>
 *
 * <p>{@code refundedAmountMinor} 为渠道实际退款金额（最小货币单位），仅 SUCCESS 时非 {@code null}；
 * 缺省（{@code null}）时 refund-service 视为全额退款，保持向后兼容。</p>
 */
public record RefundAttemptResponse(Long refundId, String status, String channelReference,
                                    Long refundedAmountMinor) {
}
