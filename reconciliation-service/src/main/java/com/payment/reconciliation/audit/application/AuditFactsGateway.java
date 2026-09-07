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

    /**
     * 渠道账单加载（含口径标志，spec 022 T433）：{@code officialFile=true} 表示命中周期
     * 正式账单文件（双向比对：正向 + 反向短款 + 重复流水）；false 表示回退默认 fixture
     * （非正式账单全集，仅正向比对）。默认实现沿用 {@link #channelStatements(String)}，
     * 一律视为非正式账单（保守口径）。
     */
    default ChannelStatementLoad channelStatementLoad(String period) {
        return new ChannelStatementLoad(channelStatements(period), false);
    }

    /** 渠道账单加载结果：条目 + 是否命中周期正式账单文件。 */
    record ChannelStatementLoad(List<com.payment.reconciliation.domain.ChannelStatement> statements,
                                boolean officialFile) {
    }

    /** 平衡性快捷视图。 */
    static boolean balanced(Map<String, Long> diffByCurrency) {
        return diffByCurrency.values().stream().allMatch(d -> d == 0L);
    }
}
