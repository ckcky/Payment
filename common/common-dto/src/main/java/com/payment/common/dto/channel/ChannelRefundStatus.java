package com.payment.common.dto.channel;

/**
 * 渠道侧退款结论（跨域口径，spec 037 / FR-004）。
 *
 * <p>与 {@link ChannelPayStatus} 同构、<b>刻意不合并</b>：退款与支付的终态收敛路径不同
 * （退款有「在途」概念，支付没有），且两者的失败原因集合不一致。合并成一个枚举会让
 * 消费端靠上下文猜语义——与 {@link PayCredential.Kind#REDIRECT_URL} / {@code H5_URL}
 * 不合并同一条理由。</p>
 *
 * <h3>三档语义</h3>
 * <ul>
 *   <li>{@link #SUCCESS}：渠道明确退款成功（ADR-0016：退款恒全退，无部分退款档）；</li>
 *   <li>{@link #FAILURE}：渠道明确拒绝退款，<b>不重试</b>；</li>
 *   <li>{@link #UNKNOWN}：无结论，须由主动查询收敛。</li>
 * </ul>
 */
public enum ChannelRefundStatus {

    /** 渠道明确退款成功。 */
    SUCCESS,
    /** 渠道明确拒绝退款。 */
    FAILURE,
    /** 无结论（超时 / 断连 / 不完整响应）。 */
    UNKNOWN;

    /** 是否已可进入终态（{@code UNKNOWN} 不是终态，须由主动查询收敛）。 */
    public boolean isTerminal() {
        return this != UNKNOWN;
    }

    /** 是否明确成功。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
