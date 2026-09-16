package com.payment.payment.limit.domain;

/**
 * 额度操作类型（spec 027 / FR-015，ADR-0071 D2/D4）。
 *
 * <p>每种操作在 {@code limit_operations} 里对应一条流水，唯一键为
 * {@code UK(biz_no = paymentNo, op_type)}——这是幂等的<b>数据库级</b>兜底（INV-4）：
 * 撞键即跳过金额变更，不重复加/减。</p>
 *
 * <p><b>为什么需要 EXPIRED</b>（D11/D13）：{@code deferChannel=true} 的收银台路径下
 * 「点了支付不回调」会让 {@code pending} 永久挂住，等价于一个可主动触发的拒绝服务面。
 * TTL 到期即释放，但<b>绝不反向修改 {@code payments.status}</b>（守「不猜成败」）。</p>
 */
public enum LimitOperationType {

    /** 预占：{@code pending += a}（建单前，原子 UPDATE，0 行 = 超限）。 */
    RESERVE,
    /** 确认：{@code used += a, pending = GREATEST(0, pending - a)}（支付 SUCCEEDED）。 */
    CONFIRM,
    /** 释放：{@code pending = GREATEST(0, pending - a)}（支付 FAILED / CLOSED）。 */
    RELEASE,
    /** 过期释放：同 RELEASE 的金额效果，但语义是「TTL 到期」而非「渠道给了失败结果」。 */
    EXPIRED;

    /** 该操作是否终结一笔在途预占（用于补偿扫描判定「已结算」）。 */
    public boolean isTerminal() {
        return this != RESERVE;
    }
}
