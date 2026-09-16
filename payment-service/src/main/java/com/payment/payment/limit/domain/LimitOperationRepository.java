package com.payment.payment.limit.domain;

import java.util.List;
import java.util.Optional;

/**
 * 额度操作流水仓储（spec 027 / FR-015，ADR-0071 D4）。
 *
 * <p>写入即幂等：{@code insert} 撞 {@code UK(biz_no, op_type, period)} 时返回
 * {@code false}（未插入），调用方据此<b>跳过金额变更</b>——这是三道闸门的第 2 道。</p>
 *
 * <p><b>为什么唯一键必须带 {@code period}</b>：一笔支付会同时在日 / 月 / 年三个周期上
 * 预占。若 UK 只有 {@code (biz_no, op_type)}，第一个周期（DAY）插入的 {@code RESERVE}
 * 会让后两周期的同类插入全部撞键 → 被误判为「已执行」→ 月 / 年额度<b>根本不会被占用</b>，
 * 限额静默失效。带上 {@code period} 后，「一张支付单 × 一个周期 × 一种操作」恰好一条流水，
 * 与「三周期各自独立判定」（FR-010）严格对应。</p>
 */
public interface LimitOperationRepository {

    /**
     * 幂等插入一条流水。
     *
     * @return {@code true} = 本次真正插入（调用方应继续变更金额）；
     *         {@code false} = 撞唯一键（该 {@code (bizNo, opType, period)} 已存在，调用方必须跳过金额变更）
     */
    boolean insert(LimitOperation operation);

    /**
     * 该支付单在某周期是否已有某类流水（补偿扫描判定「已结算」、预占幂等闸门）。
     *
     * @param period 周期；{@code null} = 不限定周期（任一周期存在即为真）
     */
    boolean exists(String bizNo, LimitOperationType opType, LimitPeriod period);

    /** 该支付单的全部流水（按 id 升序）。 */
    List<LimitOperation> findByBizNo(String bizNo);

    /**
     * 取一条流水。
     *
     * @param period 周期；{@code null} = 不限定周期（返回最先命中一条）
     */
    Optional<LimitOperation> find(String bizNo, LimitOperationType opType, LimitPeriod period);

    /**
     * 删除一条流水（仅用于「预占失败后清理」这一狭窄场景）。
     *
     * <p>为什么需要它：预占的三个周期里若后一个超限，前面已插入的 {@code RESERVE} 流水必须
     * 清掉，否则同一 {@code paymentNo} 重试时会被幂等闸门判为「已预占」而放行一笔
     * <b>实际未预占</b>的支付——那是限额静默失效。该 {@code paymentNo} 的建单事务即将
     * 整体回滚、不会被任何已提交数据引用，故删除是安全的。</p>
     */
    void delete(LimitOperation operation);

    /**
     * 查该用户的未结算在途（{@code RESERVE} 存在、且无任何终态流水），供惰性回收判定
     * （FR-038，走 {@code idx_limitop_user_type}）。
     */
    List<LimitOperation> findUnsettledReserves(String userId);
}
