package com.payment.ledger.domain;

import java.util.Objects;

/**
 * 账户余额投影行（spec 031 §10 / ADR-0079 方案②）：{@code account_balances} 是
 * Projection / Read Model——事实源恒为 {@code ledger_entries}，本表可校验、可重建。
 */
public final class AccountBalance {

    private final long accountInstanceId;
    private final String currency;
    private final long debitTotal;
    private final long creditTotal;
    private final long entryCount;
    private final long lastEntryId;

    public AccountBalance(long accountInstanceId, String currency, long debitTotal, long creditTotal,
                          long entryCount, long lastEntryId) {
        this.accountInstanceId = accountInstanceId;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.debitTotal = debitTotal;
        this.creditTotal = creditTotal;
        this.entryCount = entryCount;
        this.lastEntryId = lastEntryId;
    }

    /** 对外余额按科目正常余额方向取号：借余科目 = 借-贷；贷余科目 = 贷-借。 */
    public long balance(AccountDefinition.NormalSide normalSide) {
        return normalSide == AccountDefinition.NormalSide.DEBIT
                ? debitTotal - creditTotal
                : creditTotal - debitTotal;
    }

    public long getAccountInstanceId() {
        return accountInstanceId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getDebitTotal() {
        return debitTotal;
    }

    public long getCreditTotal() {
        return creditTotal;
    }

    public long getEntryCount() {
        return entryCount;
    }

    public long getLastEntryId() {
        return lastEntryId;
    }
}
