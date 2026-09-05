package com.payment.common.dto.rpc;

/**
 * 自动退款命令响应（order-service → payment-service，Feature 016 / ADR-0054）。
 *
 * <p>{@code refundNo} 为退款业务单号（RF+雪花）；{@code status} 为退款单终态
 * （SUCCEEDED / FAILED / UNKNOWN / REJECTED），最终失败由 payment 侧重试耗尽后转人工/对账兜底。</p>
 */
public record RefundCommandResponse(String refundNo, String status) {
}
