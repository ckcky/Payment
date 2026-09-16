package com.payment.payment.limit.application;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

/**
 * 在途占用过期索引（spec 027 / FR-036~038，ADR-0071 D13）。
 *
 * <p><b>这是 Redis 在本域的唯一用途，且必须守住一条铁律</b>（INV-9.1）：
 * Redis <b>绝不参与</b> {@code used} / {@code pending} 的权威计算——额度计数的权威
 * 恒在 DB。本接口只能回答「这笔在途是否还活着」，不能回答「用户花了多少」。</p>
 *
 * <p><b>失效方向是单向的</b>（INV-9.2 / INV-9.4）：Redis 出错只能让约束变松
 * （提前释放 → 显式软超限 + 留痕），<b>绝不能</b>让已发生的事实被篡改。
 * 因此：</p>
 * <ul>
 *   <li>{@link #mark} / {@link #clear} 失败：<b>不抛</b>，只记指标——建单路径不得因 Redis 抖动而失败；</li>
 *   <li>{@link #alive} 无法判定（Redis 不可用）：返回 {@code null}，调用方<b>跳过回收</b>
 *       （保守占用，INV-9.2），<b>不得</b>当作「全部已过期」。</li>
 * </ul>
 */
public interface LimitExpiryIndex {

    /**
     * 标记一笔在途占用的存活期限（RESERVE 成功后调用）。
     *
     * @param paymentNo   支付单号（key 的业务部分）
     * @param amountMinor 金额（分）；仅作可读性，判定不读它
     * @param ttl         存活时长（{@code payment.limit.reserve-ttl}）
     */
    void mark(String paymentNo, long amountMinor, Duration ttl);

    /** 清除标记（CONFIRM / RELEASE / EXPIRED 后，尽力而为）。 */
    void clear(String paymentNo);

    /**
     * 批量判定哪些仍存活。
     *
     * @param paymentNos 待判定的支付单号
     * @return 仍在 Redis 中的支付单号集合；
     *         {@code null} = <b>无法判定</b>（Redis 不可用）→ 调用方必须跳过回收（INV-9.2）
     */
    Set<String> alive(Collection<String> paymentNos);
}
