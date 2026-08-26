package com.payment.refund.domain;

/**
 * 退款资格决策（值对象）：由 {@link RefundPolicy} 生成，记录批准/拒绝结论与原因。
 */
public record RefundDecision(Decision decision, String reason) {

    public enum Decision {
        APPROVED,
        REJECTED
    }

    public static RefundDecision approved() {
        return new RefundDecision(Decision.APPROVED, null);
    }

    public static RefundDecision rejected(String reason) {
        return new RefundDecision(Decision.REJECTED, reason);
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED;
    }
}
