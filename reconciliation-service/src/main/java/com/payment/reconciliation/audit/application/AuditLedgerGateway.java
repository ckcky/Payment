package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AdjustmentPolicy;

import java.util.List;

/**
 * 审计记账出站网关（spec 017 / FR-014、FR-015）：挂账 / 调账经 ledger 标准记账通道
 * 落 {@code source_type=ADJUSTMENT} 分录。幂等键由 {@link AdjustmentPolicy} 统一为
 * {@code adjust:{adjustNo}}，重复提交返回首次结果。
 */
public interface AuditLedgerGateway {

    /**
     * 记账（幂等）。
     *
     * @param idempotencyKey adjust:{adjustNo}
     * @param adjustNo       sourceId = AD 单号
     * @param currency       币种
     * @param entries        借贷平衡分录
     * @return ledger 侧 posting 单号
     */
    PostingResult postAdjustment(String idempotencyKey, String adjustNo, String currency,
                                 List<AdjustmentPolicy.PostingEntry> entries);

    /** 记账结果。 */
    record PostingResult(String postingNo, String postingId) {
    }
}
