package com.payment.ledger.domain;

/**
 * 科目（Chart of Accounts）：MVP 为系统预置的固定科目表（ADR-0008）。
 *
 * <p>ID 与 {@code deployment/schema/09-ledger-schema.sql} 的种子数据一一对应；
 * MVP 不动态新建科目。多币种按 {@code currency} 维度隔离（当前仅 CNY）。</p>
 */
public enum Account {

    /** 客户/平台持有的已收资金。 */
    CUSTOMER_CASH(1L, "CUSTOMER_CASH", AccountType.ASSET),
    /** 应付商户净额。 */
    MERCHANT_PAYABLE(2L, "MERCHANT_PAYABLE", AccountType.LIABILITY),
    /** 平台手续费收入。 */
    PLATFORM_FEE_REVENUE(3L, "PLATFORM_FEE_REVENUE", AccountType.REVENUE),
    /** 已结算待出款（MVP 不出款）。 */
    SETTLEMENT_PAYABLE(4L, "SETTLEMENT_PAYABLE", AccountType.LIABILITY),
    /** 待处理差错款（spec 017 / ADR-0065）：挂账过渡科目，期末余额应趋于 0。 */
    SUSPENSE(5L, "SUSPENSE", AccountType.ASSET);

    private final long id;
    private final String code;
    private final AccountType type;

    Account(long id, String code, AccountType type) {
        this.id = id;
        this.code = code;
        this.type = type;
    }

    public long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public AccountType getType() {
        return type;
    }

    public static Account of(long id) {
        for (Account account : values()) {
            if (account.id == id) {
                return account;
            }
        }
        throw new IllegalArgumentException("unknown account id: " + id);
    }

    /** 科目类型：资产 / 负债 / 收入 / 费用 / 权益。 */
    public enum AccountType {
        ASSET,
        LIABILITY,
        REVENUE,
        EXPENSE,
        EQUITY
    }
}
