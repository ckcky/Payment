package com.payment.refund.domain;

/**
 * 退款资格与可退款金额规则（纯领域函数，无副作用）。
 *
 * <p>可退款金额 = 已支付金额 − 累计成功/处理中退款金额；退款币种必须与支付币种一致；
 * 金额必须为正且累计退款不得超过可退款金额。这些规则是资金正确性的核心约束。</p>
 */
public final class RefundPolicy {

    private RefundPolicy() {
    }

    /** 计算当前可退款金额（非负）。 */
    public static long refundableAmount(long paidAmountMinor, long cumulativeRefundedMinor) {
        return Math.max(0, paidAmountMinor - cumulativeRefundedMinor);
    }

    /**
     * 判断退款申请是否可批准。
     *
     * @param requestedMinor         申请退款金额（最小货币单位）
     * @param requestedCurrency      申请退款币种
     * @param paidAmountMinor        原支付已确认成功金额
     * @param paidCurrency           原支付币种
     * @param cumulativeRefundedMinor 该支付累计已退款（成功/处理中）金额
     */
    public static RefundDecision decide(long requestedMinor, String requestedCurrency,
                                        long paidAmountMinor, String paidCurrency,
                                        long cumulativeRefundedMinor) {
        if (!requestedCurrency.equals(paidCurrency)) {
            return RefundDecision.rejected("currency mismatch: refund " + requestedCurrency
                    + " vs payment " + paidCurrency);
        }
        if (requestedMinor <= 0) {
            return RefundDecision.rejected("refund amount must be positive, was " + requestedMinor);
        }
        if (cumulativeRefundedMinor + requestedMinor > paidAmountMinor) {
            return RefundDecision.rejected("refund exceeds refundable amount: requested " + requestedMinor
                    + " + alreadyRefunded " + cumulativeRefundedMinor
                    + " > paid " + paidAmountMinor);
        }
        return RefundDecision.approved();
    }
}
