package com.payment.payment.limit.domain;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * 限额周期（spec 027 / FR-002，D10）：DAY / MONTH / YEAR 三档。
 *
 * <p><b>周期重置不用定时任务</b>（D10）：本枚举只提供「给定时刻属于哪个周期的起点」的现算，
 * 请求进来算一次、查不到行就建行；跨周期的旧行自然闲置（保留为审计），零调度器、
 * 零跨天临界问题。</p>
 *
 * <p>周期口径统一按 <b>UTC</b> 切分（与 {@code payments.created_at} 的存储口径一致），
 * 避免「服务器时区变了，同一天的额度边界跟着漂」。</p>
 */
public enum LimitPeriod {

    /** 日额度：当天 00:00（UTC）。 */
    DAY,
    /** 月额度：当月 1 号（UTC）。 */
    MONTH,
    /** 年额度：当年 1 月 1 号（UTC）。 */
    YEAR;

    /**
     * 给定时刻所属本周期的起始日（UTC）。
     *
     * @param atUtc 判定时刻（UTC）
     * @return 该周期起始日；如 DAY→当天、MONTH→本月 1 号、YEAR→1 月 1 号
     */
    public LocalDate periodStart(java.time.Instant atUtc) {
        LocalDate date = atUtc.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (this) {
            case DAY -> date;
            case MONTH -> date.with(TemporalAdjusters.firstDayOfMonth());
            case YEAR -> date.with(TemporalAdjusters.firstDayOfYear());
        };
    }

    /**
     * 本周期结束日（含），仅供查询接口展示与排障使用——<b>判定路径不读它</b>。
     */
    public LocalDate periodEnd(java.time.Instant atUtc) {
        LocalDate start = periodStart(atUtc);
        return switch (this) {
            case DAY -> start;
            case MONTH -> start.with(TemporalAdjusters.lastDayOfMonth());
            case YEAR -> start.with(TemporalAdjusters.lastDayOfYear());
        };
    }

    /** 解析周期名（大小写不敏感），非法值抛出带合法清单的异常。 */
    public static LimitPeriod parse(String name) {
        if (name == null) {
            throw new IllegalArgumentException("period is required; legal values: "
                    + java.util.Arrays.toString(values()));
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("illegal period '" + name + "'; legal values: "
                    + java.util.Arrays.toString(values()));
        }
    }
}
