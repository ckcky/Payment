package com.payment.common.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 金额值对象（金额 + 币种），不可变。
 *
 * <p>铁律（Constitution §2.2）：金额以最小货币单位（整数 {@code long}）存储，全库禁止
 * {@code float}/{@code double} 表示或计算金额。本类不提供任何以浮点入参的构造/factory。</p>
 */
public final class Money implements Comparable<Money> {

    private final long amountMinor;
    private final Currency currency;

    private Money(long amountMinor, Currency currency) {
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    /** 用最小货币单位构造，例如 {@code Money.ofMinor(100, "USD")} 表示 1.00 USD。 */
    public static Money ofMinor(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public static Money ofMinor(long amountMinor, Currency currency) {
        return new Money(amountMinor, Objects.requireNonNull(currency, "currency"));
    }

    /** 指定币种的零金额。 */
    public static Money zero(String currencyCode) {
        return ofMinor(0L, currencyCode);
    }

    /**
     * 用明确 scale 的 {@link BigDecimal} 构造，禁止浮点。scale 为最小货币单位的位数（典型币种为 2）。
     * 使用 {@link RoundingMode#UNNECESSARY}，任何精度损失都会抛出异常，而不是静默舍入。
     */
    public static Money of(BigDecimal amount, String currencyCode, int scale) {
        Currency currency = Currency.getInstance(currencyCode);
        long minor = amount.setScale(scale, RoundingMode.UNNECESSARY)
                .movePointRight(scale)
                .longValueExact();
        return new Money(minor, currency);
    }

    /** 最小货币单位金额（如分）。 */
    public long getAmountMinor() {
        return amountMinor;
    }

    /** ISO-4217 币种代码（如 {@code USD}、{@code CNY}）。 */
    public String getCurrencyCode() {
        return currency.getCurrencyCode();
    }

    public boolean isZero() {
        return amountMinor == 0L;
    }

    public boolean isPositive() {
        return amountMinor > 0L;
    }

    public boolean isNegative() {
        return amountMinor < 0L;
    }

    /** 同币种相加，溢出抛异常（资金正确性）。 */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    /** 同币种相减，溢出抛异常。 */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    /** 取反。 */
    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + getCurrencyCode() + " vs " + other.getCurrencyCode());
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(this.amountMinor, other.amountMinor);
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return compareTo(other) <= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amountMinor == other.amountMinor && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinor, currency);
    }

    @Override
    public String toString() {
        return amountMinor + " " + getCurrencyCode();
    }
}
