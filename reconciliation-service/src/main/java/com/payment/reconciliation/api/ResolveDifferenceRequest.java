package com.payment.reconciliation.api;

/**
 * 处理差异请求：指定差异引用与处理说明。
 */
public record ResolveDifferenceRequest(String reference, String resolutionNote) {
}
