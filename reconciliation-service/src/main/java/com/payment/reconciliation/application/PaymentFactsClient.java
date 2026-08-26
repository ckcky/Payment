package com.payment.reconciliation.application;

import com.payment.reconciliation.domain.PlatformFact;

import java.util.List;

/**
 * reconciliation-service → payment-service 的出站同步 RPC 端口：读取已确认支付事实。
 * 生产用 Feign 实现，测试用 fake。只读，不修改原始支付事实。
 */
public interface PaymentFactsClient {

    List<PlatformFact> fetchConfirmedFacts();
}
