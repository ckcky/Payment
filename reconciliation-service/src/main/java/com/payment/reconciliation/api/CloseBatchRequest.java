package com.payment.reconciliation.api;

/**
 * 关闭对账批次请求（ADR-0019）：收口由资金运营/运维显式触发，记录操作人。
 */
public record CloseBatchRequest(String operator) {
}
