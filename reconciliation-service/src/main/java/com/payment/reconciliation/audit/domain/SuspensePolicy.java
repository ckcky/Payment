package com.payment.reconciliation.audit.domain;

import com.payment.common.dto.rpc.AccountCode;

/**
 * 挂账方向判定（FR-014，纯函数）：账少记 → 借资金 / 贷 SUSPENSE；账多记 → 反向。
 *
 * <p>「账少记」= 账本金额 &lt; 业务口径（如 MISSING_POSTING）；「账多记」= 账本多记
 * （如 ORPHAN / DUPLICATE）。AMOUNT_MISMATCH 按实际与期望差的符号判定。</p>
 *
 * <p>spec 031：科目码常量一律引用事件契约枚举 {@link AccountCode}（§7.7 门禁：
 * 科目 code 字面量禁落他域）；新事件的现金腿为 BANK_CASH（CUSTOMER_CASH 判 LEGACY，
 * 仅承接历史分录）。方向拼装已删除——由账本 {@code AdjustmentRule} 推导（§9）。</p>
 */
public final class SuspensePolicy {

    public static final String CUSTOMER_CASH = AccountCode.CUSTOMER_CASH.name();
    public static final String BANK_CASH = AccountCode.BANK_CASH.name();
    public static final String SUSPENSE = AccountCode.SUSPENSE.name();
    public static final String MERCHANT_PAYABLE = AccountCode.MERCHANT_PAYABLE.name();
    public static final String FEE_REVENUE = AccountCode.FEE_REVENUE.name();
    public static final String CHANNEL_RECEIVABLE = AccountCode.CHANNEL_RECEIVABLE.name();
    public static final String SETTLEMENT_PAYABLE = AccountCode.SETTLEMENT_PAYABLE.name();

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
}
