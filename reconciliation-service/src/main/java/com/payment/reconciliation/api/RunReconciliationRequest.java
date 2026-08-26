package com.payment.reconciliation.api;

/**
 * 执行对账请求：指定对账周期。
 */
public record RunReconciliationRequest(String period) {
}
