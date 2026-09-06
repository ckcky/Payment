package com.payment.settlement.application;

import java.util.List;

/**
 * settlement-service → reconciliation-service 的出站同步 RPC 端口：审计结算门禁
 * （spec 017 / ADR-0065 / plan §6.1 分级门禁）。
 *
 * <p>BLOCK 语义：① 账本借贷不平衡（硬拦）；② 存在 {@code BLOCKER} 且 {@code PENDING}
 * （未挂账、未调账）的审计差异。已挂账（SUSPENDED）/ 已调账（ADJUSTED）差异放行留痕。</p>
 */
public interface AuditGateClient {

    /** 查询某周期的审计门禁裁决；网络/服务异常由实现归一化上抛（调用方 fail-closed）。 */
    AuditGateDecision getSettlementGate(String period);

    /** 恒放行实现：开关关闭时使用，行为与 spec 017 之前完全一致。 */
    static AuditGateClient disabled() {
        return period -> new AuditGateDecision("ALLOW", true, List.of());
    }
}
