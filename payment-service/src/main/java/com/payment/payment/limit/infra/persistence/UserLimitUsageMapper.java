package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 周期额度占用 Mapper：承载 spec 027 / FR-006~008 的<b>原子金额变更</b>。
 *
 * <p>为什么用注解 SQL 而非 MyBatis-Plus 的实体内存运算：判定必须在<b>一条语句内</b>
 * 完成「读取当前占用 + 比较限额 + 写入新占用」，否则「先查后判」的竞态会让 N 笔并发
 * 全部读到「未超限」再全部放行（ADR-0071 备选方案否决理由①）。返回值为影响行数，
 * {@code 0} 即语义失败。</p>
 *
 * <p>跨库兼容：{@code GREATEST} 与 {@code ON DUPLICATE KEY UPDATE} 均为 MySQL / H2
 * （{@code MODE=MySQL}）双方言支持。</p>
 */
public interface UserLimitUsageMapper extends BaseMapper<UserLimitUsageEntity> {

    /**
     * 幂等建行：不存在则插入零占用。撞唯一键时不改任何值（{@code id = id} 是空操作），
     * 保证「并发建行」不会覆盖已有占用。
     */
    @Insert("""
            INSERT INTO user_limit_usage
                (user_id, currency_code, period, period_start, used_minor, pending_minor,
                 created_at, updated_at, version)
            VALUES
                (#{userId}, #{currencyCode}, #{period}, #{periodStart}, 0, 0,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int ensureRow(@Param("userId") String userId,
                  @Param("currencyCode") String currencyCode,
                  @Param("period") String period,
                  @Param("periodStart") LocalDate periodStart);

    /**
     * 原子预占（FR-006）：{@code pending += a}，仅当 {@code used + pending + a <= limit}。
     *
     * <p>{@code AND period_start = #{periodStart}} 是跨周期安全阀：若请求落在新周期而
     * 行还是旧周期的（理论竞态窗口），本语句 0 行 → 走 {@code ensureRow} 后重试，
     * 绝不在旧周期行上累加。</p>
     *
     * @return 影响行数；{@code 0} = 超限（或行不匹配），调用方抛 {@code LIMIT_EXCEEDED}
     */
    @Update("""
            UPDATE user_limit_usage
               SET pending_minor = pending_minor + #{amountMinor},
                   updated_at = CURRENT_TIMESTAMP,
                   version = version + 1
             WHERE user_id = #{userId}
               AND currency_code = #{currencyCode}
               AND period = #{period}
               AND period_start = #{periodStart}
               AND used_minor + pending_minor + #{amountMinor} <= #{limitMinor}
            """)
    int reserveIfWithinLimit(@Param("userId") String userId,
                             @Param("currencyCode") String currencyCode,
                             @Param("period") String period,
                             @Param("periodStart") LocalDate periodStart,
                             @Param("amountMinor") long amountMinor,
                             @Param("limitMinor") long limitMinor);

    /**
     * 确认（SUCCEEDED）：{@code used += a}、{@code pending = GREATEST(0, pending - a)}。
     *
     * <p><b>无条件累加</b>（D12 软超限）：WHERE 只认身份与周期，不比较限额。
     * {@code GREATEST(0, …)} 保证 {@code pending} 不为负（INV-7）。</p>
     */
    @Update("""
            UPDATE user_limit_usage
               SET used_minor = used_minor + #{amountMinor},
                   pending_minor = GREATEST(0, pending_minor - #{amountMinor}),
                   updated_at = CURRENT_TIMESTAMP,
                   version = version + 1
             WHERE user_id = #{userId}
               AND currency_code = #{currencyCode}
               AND period = #{period}
            """)
    int confirm(@Param("userId") String userId,
                @Param("currencyCode") String currencyCode,
                @Param("period") String period,
                @Param("amountMinor") long amountMinor);

    /** 释放（FAILED / CLOSED / EXPIRED）：{@code pending = GREATEST(0, pending - a)}。 */
    @Update("""
            UPDATE user_limit_usage
               SET pending_minor = GREATEST(0, pending_minor - #{amountMinor}),
                   updated_at = CURRENT_TIMESTAMP,
                   version = version + 1
             WHERE user_id = #{userId}
               AND currency_code = #{currencyCode}
               AND period = #{period}
            """)
    int release(@Param("userId") String userId,
                @Param("currencyCode") String currencyCode,
                @Param("period") String period,
                @Param("amountMinor") long amountMinor);
}
