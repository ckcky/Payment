package com.payment.reconciliation.domain;

/**
 * 自动处置策略枚举（spec 032 §16 H-032-6 / plan §2.7，G5 保守起步）：
 * 仅 {@code SMALL_CHANNEL_ONLY_SUSPEND}——只对 CHANNEL_ONLY（渠道长款、方向确定）
 * 的 PENDING / CNY / 金额 ≤ 上限差异自动挂账；其余一律人工。配置开关
 * {@code reconciliation.autodisposition.enabled}（默认 false）+ {@code max-amount-minor}
 * （默认 0 = 关闭）双门，宁可少做不可做错。
 */
public enum AutoDispositionPolicy {

    SMALL_CHANNEL_ONLY_SUSPEND;

    /**
     * 策略门（纯函数，逐条可单测）：
     * 仅 CHANNEL_ONLY、PENDING、CNY、0 &lt; 金额 ≤ maxAmountMinor（上限 &lt;= 0 = 关闭）。
     */
    public boolean eligible(DifferenceType kind, String status, long amountMinor, String currency,
                            long maxAmountMinor) {
        if (this != SMALL_CHANNEL_ONLY_SUSPEND) {
            return false;
        }
        return kind == DifferenceType.CHANNEL_ONLY
                && "PENDING".equals(status)
                && "CNY".equals(currency)
                && maxAmountMinor > 0
                && amountMinor > 0
                && amountMinor <= maxAmountMinor;
    }
}
