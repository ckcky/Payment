package com.payment.payment.limit.domain;

/**
 * 用户限额配置（spec 027 / FR-001~003，ADR-0071 D2）。
 *
 * <p>一行承载日 / 月 / 年三个额度，{@code 0 = 该周期不限}（FR-012）。<b>查不到行 = 不限额</b>，
 * 与「三个额度都是 0」等价——这是兼容性的硬要求（FR-024）：否则
 * {@code deployment/demo/traffic-gen.sh} 的持续流量与 nightly E2E 会被 409 打断。</p>
 *
 * <p>金额一律最小货币单位（{@code long} 分，ADR-0010）。</p>
 */
public record UserPaymentLimit(
        Long id,
        String userId,
        String currencyCode,
        long dailyLimitMinor,
        long monthlyLimitMinor,
        long yearlyLimitMinor,
        String status,
        Integer version) {

    /** 配置状态：生效。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 配置状态：停用（视同不限额，便于临时放行而不删配置）。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 该周期是否受限（额度 &gt; 0 且配置生效）。0 或 DISABLED = 不限。 */
    public boolean isLimited(LimitPeriod period) {
        if (!STATUS_ACTIVE.equalsIgnoreCase(status)) {
            return false;
        }
        return limitOf(period) > 0;
    }

    /** 取某周期的额度（分）；0 = 不限。 */
    public long limitOf(LimitPeriod period) {
        return switch (period) {
            case DAY -> dailyLimitMinor;
            case MONTH -> monthlyLimitMinor;
            case YEAR -> yearlyLimitMinor;
        };
    }

    /** 是否存在任意一个受限周期（全 0 视同无配置，建单路径可整段跳过）。 */
    public boolean hasAnyLimit() {
        return isLimited(LimitPeriod.DAY)
                || isLimited(LimitPeriod.MONTH)
                || isLimited(LimitPeriod.YEAR);
    }

    public static UserPaymentLimit of(String userId, String currencyCode,
                                      long dailyLimitMinor, long monthlyLimitMinor,
                                      long yearlyLimitMinor) {
        return new UserPaymentLimit(null, userId, currencyCode,
                dailyLimitMinor, monthlyLimitMinor, yearlyLimitMinor, STATUS_ACTIVE, null);
    }
}
