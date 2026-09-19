package com.payment.payment.limit.domain;

/**
 * 用户周期额度占用（spec 027 / FR-005~010，ADR-0071 D2/D3）。
 *
 * <p><b>为什么必须双金额</b>：只统计 {@code used}（已确认）的话，并发 N 笔同日请求会同时
 * 读到「未超限」再全部放行，日限额被直接击穿。{@code pending}（在途保守占用）把「已受理但
 * 结果未定」的金额也计入判定，与退款域 {@code RefundPolicy}「按申请额累计防超退」同口径。</p>
 *
 * <p>本 record 是<b>读模型</b>：所有金额变更都走仓储的原子 UPDATE（{@link LimitUsageRepository}），
 * 不做「读-改-写」——那正是并发穿透的成因。</p>
 *
 * <p>{@link #available} 与 {@link #overrun} 都不做 clamp：{@link #available} 允许为负
 * （软超限的可见表达，D12），{@link #overrun} 即「已发生事实超过限额」的部分。</p>
 */
public record LimitUsage(
        Long id,
        String userId,
        String currencyCode,
        LimitPeriod period,
        java.time.LocalDate periodStart,
        long usedMinor,
        long pendingMinor,
        Integer version) {

    /** 已占用总额（已确认 + 在途）。 */
    public long occupiedMinor() {
        return usedMinor + pendingMinor;
    }

    /**
     * 剩余可用额度（分）。<b>允许为负</b>——负值即软超限的可见表达（D12），
     * 不 clamp 到 0，否则「超了多少」这一关键信息就被抹掉。
     */
    public long availableMinor(long limitMinor) {
        return limitMinor - occupiedMinor();
    }

    /** 软超限金额（分）：已发生事实超出限额的部分；未超为 0（FR-039 / D12）。 */
    public long overrunMinor(long limitMinor) {
        return Math.max(0L, occupiedMinor() - limitMinor);
    }

    /** 空占用（该周期尚无任何记录时的读模型）。 */
    public static LimitUsage empty(String userId, String currencyCode, LimitPeriod period,
                                   java.time.LocalDate periodStart) {
        return new LimitUsage(null, userId, currencyCode, period, periodStart, 0L, 0L, null);
    }
}
