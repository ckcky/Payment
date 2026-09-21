package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AdjustmentPolicy;

/**
 * 审计记账出站网关（spec 017 / FR-014、FR-015，031 事件化 / ADR-0077）：挂账 / 调账经
 * ledger 标准事件通道发 {@code ADJUSTMENT} 事件（source_type=RECONCILIATION、source_id=adjustNo）。
 * 幂等键由账本按 {@code ADJUSTMENT:{adjustNo}} 派生，重复提交回放首次结果；
 * 记账失败 MUST NOT 静默——处置必须留痕，失败直接上抛（NFR-008）。
 */
public interface AuditLedgerGateway {

    /**
     * 记账（幂等）。
     *
     * @param adjustNo 处置单号 AD（账本 sourceId）
     * @param currency 币种
     * @param plan     转账语义事件计划（方向/分录由账本 AdjustmentRule 展开）
     * @return ledger 侧 posting 单号
     */
    PostingResult postAdjustment(String adjustNo, String currency, AdjustmentPolicy.AdjustPlan plan);

    /** 记账结果。 */
    record PostingResult(String postingNo, String postingId) {
    }
}
