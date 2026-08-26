package com.payment.settlement.application;

/**
 * settlement-service → reconciliation-service 的出站同步 RPC 端口：查询某周期结算汇总。
 * 生产用 Feign 实现，测试用 fake。
 */
public interface ReconciliationClient {

    ReconciliationSummary getSettlementSummary(String period);
}
