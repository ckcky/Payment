package com.payment.payment.api;

/**
 * 未知退款收敛请求：携带权威结果（SUCCEEDED / FAILED / UNKNOWN）。
 */
public record ResolveRefundRequest(String status) {
}
