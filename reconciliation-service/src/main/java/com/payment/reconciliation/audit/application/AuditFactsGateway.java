package com.payment.reconciliation.audit.application;

import java.util.List;
import java.util.Map;

/**
 * 审计只读事实网关（spec 017 / FR-004）：四核对的全部输入。
 * 任一数据源读取失败直接上抛（NFR-008 失效安全，绝不静默产出「无差异」）。
 */
public interface AuditFactsGateway {

    /** 已确认资金事实：支付 + 退款 + 结算三来源（仅 SUCCEEDED，FR-012）。period 用于拉取该周期结算事实。 */
    List<CertificateFact> confirmedFacts(String period);

    /** 账本全部分录（含 ADJUSTMENT，只读）。 */
    List<LedgerPostingView> ledgerPostings();

    /** 账本借贷平衡（FR-005 / FR-019 门禁硬条件）。 */
    LedgerBalance ledgerBalance();

    /** 结算批次审计事实（跨账核对用，含非 SUCCEEDED 供过滤）。 */
    List<SettlementBatchFact> settlementFacts(String period);

    /** 渠道账单（沿用 006 加载器口径；REAL 核对用）。 */
    List<com.payment.reconciliation.domain.ChannelStatement> channelStatements(String period);

    /** 平衡性快捷视图。 */
    static boolean balanced(Map<String, Long> diffByCurrency) {
        return diffByCurrency.values().stream().allMatch(d -> d == 0L);
    }
}
