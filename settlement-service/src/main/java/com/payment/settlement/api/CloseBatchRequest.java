package com.payment.settlement.api;

/**
 * 关闭结算批次请求（ADR-0023）：仅成功/失败态可关闭为终态。
 */
public record CloseBatchRequest(String operator) {
}
