package com.payment.settlement.application;

import java.util.List;

/**
 * 审计结算门禁裁决（spec 017 plan §10.3 契约的 settlement 侧视图）。
 *
 * @param decision            ALLOW / BLOCK
 * @param balanced            账本借贷是否平衡
 * @param blockingDifferences 拦截差异明细（仅 BLOCK 时非空）
 */
public record AuditGateDecision(String decision, boolean balanced, List<BlockingDifference> blockingDifferences) {

    public boolean blocked() {
        return "BLOCK".equals(decision);
    }

    /**
     * 拦截差异明细。
     *
     * @param kind       差异类型（如 MISSING_POSTING）
     * @param sourceType 来源类型（PAYMENT/REFUND/SETTLEMENT）
     * @param sourceId   来源单号
     * @param severity   严重级（BLOCKER/MAJOR/MINOR）
     * @param amountMinor 差异金额（分）
     * @param currency   币种
     */
    public record BlockingDifference(String kind, String sourceType, String sourceId,
                                     String severity, long amountMinor, String currency) {
    }
}
