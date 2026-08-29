package com.payment.reconciliation.api;

/**
 * 处理差异请求：指定差异引用、处理说明、操作人与处理时间（ADR-0019）。
 * resolvedAt 为空时由服务端取当前时间（ISO-8601）。
 */
public record ResolveDifferenceRequest(String reference, String resolutionNote, String resolvedBy, String resolvedAt) {
}
