package com.payment.settlement.api;

/**
 * 未知结算批次收敛请求：携带权威结果（SUCCEEDED / FAILED / UNKNOWN）。
 */
public record ResolveSettlementRequest(String status) {
}
