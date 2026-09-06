package com.payment.reconciliation.audit.domain;

import java.util.Map;

/**
 * 挂账方向判定（FR-014，纯函数）：账少记 → 借 CUSTOMER_CASH / 贷 SUSPENSE；账多记 → 反向。
 *
 * <p>「账少记」= 账本金额 &lt; 业务口径（如 MISSING_POSTING）；「账多记」= 账本多记
 * （如 ORPHAN / DUPLICATE）。AMOUNT_MISMATCH 按实际与期望差的符号判定。</p>
 */
public final class SuspensePolicy {

    public static final String CUSTOMER_CASH = "CUSTOMER_CASH";
    public static final String SUSPENSE = "SUSPENSE";
    public static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";
    public static final String PLATFORM_FEE_REVENUE = "PLATFORM_FEE_REVENUE";
    public static final String SETTLEMENT_PAYABLE = "SETTLEMENT_PAYABLE";

    private SuspensePolicy() {
    }

    /** 挂账方向：true = 账少记（借资金 / 贷 SUSPENSE）；false = 账多记（借 SUSPENSE / 贷资金）。 */
    public static boolean isUnderRecorded(AuditDifferenceKind kind, Long expected, Long actual) {
        return switch (kind) {
            case MISSING_POSTING -> true;
            case ORPHAN_POSTING, DUPLICATE_POSTING -> false;
            // 金额/方向/币种/账实类：按「账本(实际) < 业务(期望)」判定为账少记
            case AMOUNT_MISMATCH, DIRECTION_MISMATCH, CURRENCY_MISMATCH, LEDGER_VS_STATEMENT_BREAK ->
                    expected != null && actual != null && actual < expected;
            default -> throw new IllegalArgumentException("kind not suspendable: " + kind);
        };
    }

    /**
     * 挂账分录（借贷平衡）：返回 {direction: 科目码}，DEBIT 一条 + CREDIT 一条。
     */
    public static Map<String, String> suspendEntries(boolean underRecorded) {
        return underRecorded
                ? Map.of("DEBIT", CUSTOMER_CASH, "CREDIT", SUSPENSE)
                : Map.of("DEBIT", SUSPENSE, "CREDIT", CUSTOMER_CASH);
    }
}
