package com.payment.payment.limit.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 周期额度占用仓储（spec 027 / FR-005~010，ADR-0071 D3）。
 *
 * <p><b>判定路径全部走单条原子 UPDATE，不做读-改-写</b>——「先查后判」有竞态，
 * N 笔并发可全部放行（ADR-0071 备选方案否决理由）。所有加/减方法的返回值为
 * 「实际影响行数」，{@code 0} 即语义失败（超限 / 行不存在）。</p>
 *
 * <p>下限保护：所有减法一律 {@code GREATEST(0, x - ?)}。即使前面所有防线都被绕过，
 * 也绝不让 {@code pending_minor} 变成负数把额度撑大（INV-7 / D6）。</p>
 */
public interface LimitUsageRepository {

    /** 取某周期占用；无行返回空。 */
    Optional<LimitUsage> find(String userId, String currencyCode, LimitPeriod period);

    /** 取该用户全部周期占用（查询接口用）。 */
    List<LimitUsage> findAll(String userId, String currencyCode);

    /**
     * 确保占用行存在（幂等）：{@code INSERT ... ON DUPLICATE KEY UPDATE id = id}。
     * 首次为该用户该周期建行后，预占的原子 UPDATE 才有行可命中。
     */
    void ensureRow(String userId, String currencyCode, LimitPeriod period, LocalDate periodStart);

    /**
     * 原子预占（FR-006）：{@code pending += a}，仅当 {@code used + pending + a <= limit}。
     *
     * @return 影响行数；{@code 0} = 超限（或行不存在），调用方据此抛 {@code LIMIT_EXCEEDED}
     */
    int reserveIfWithinLimit(String userId, String currencyCode, LimitPeriod period, long amountMinor, long limitMinor);

    /**
     * 确认（SUCCEEDED）：{@code used += a}、{@code pending = GREATEST(0, pending - a)}。
     *
     * <p><b>无条件累加</b>——即使 {@code used > limit} 也不拒绝、不 clamp、不回滚（D12 软超限）：
     * 支付成功是不可逆的外部事实，为守住限额而少记 {@code used} 正是 D4 想避免的
     * 「静默少扣 → 限额失效」，比超额危险得多。</p>
     *
     * @return 影响行数
     */
    int confirm(String userId, String currencyCode, LimitPeriod period, long amountMinor);

    /**
     * 释放（FAILED / CLOSED / EXPIRED）：{@code pending = GREATEST(0, pending - a)}。
     *
     * @return 影响行数
     */
    int release(String userId, String currencyCode, LimitPeriod period, long amountMinor);
}
