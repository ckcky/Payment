package com.payment.common.dto.channel;

/**
 * 渠道侧支付结论（跨域口径，spec 037 / FR-004）。
 *
 * <p>渠道网关把渠道私有的状态语义（{@code trade_status} / {@code payment_intent.status} /
 * 抖音状态码）翻译成本枚举后才允许跨越网关边界。Payment 域<b>只认这三档</b>，
 * 不认识任何渠道私有状态码——这是「渠道差异 100% 关在网关域内」在类型层面的落点。</p>
 *
 * <h3>三档语义（沿用 {@code ChannelResult.Status} 的既有口径，ADR-0012 / Constitution §V.7）</h3>
 * <ul>
 *   <li>{@link #SUCCESS}：渠道明确成功，可进入成功终态；</li>
 *   <li>{@link #FAILURE}：渠道明确失败/拒绝，可进入失败终态，<b>不重试</b>；</li>
 *   <li>{@link #UNKNOWN}：超时 / 断连 / 不完整响应，<b>绝不臆断成败</b>，只能由主动查询收敛。</li>
 * </ul>
 *
 * <p><b>为何不合并成 boolean</b>：{@code UNKNOWN} 与 {@code FAILURE} 的资金后果完全不同——
 * 前者可能已经扣款成功，直接按失败处理会漏单。</p>
 */
public enum ChannelPayStatus {

    /** 渠道明确成功。 */
    SUCCESS,
    /** 渠道明确失败（含业务拒绝）。 */
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
