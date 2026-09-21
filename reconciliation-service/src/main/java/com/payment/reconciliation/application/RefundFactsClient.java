package com.payment.reconciliation.application;

import com.payment.reconciliation.domain.PlatformFact;

import java.util.List;

/**
 * reconciliation-service → refund-service 的出站同步 RPC 端口：读取已确认退款事实。
 * 生产用 Feign 实现，测试用 fake。只读，不修改原始退款事实。
 *
 * <p>032/H-032-1：{@code period} 非空时上游按 {@code DATE(created_at)} 期间过滤；
 * null/非日期串为兼容窗口口径（全量）。任一读取失败直接上抛（ADR-0021 失效安全）。</p>
 */
public interface RefundFactsClient {

    List<PlatformFact> fetchConfirmedFacts(String period);
}
