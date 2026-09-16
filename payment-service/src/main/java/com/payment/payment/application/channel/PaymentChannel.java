package com.payment.payment.application.channel;

/**
 * 支付渠道抽象（T035）：核心领域（Payment）不依赖具体渠道实现，只依赖本端口。
 * 每个渠道实现必须把超时/断连/不完整响应映射为 {@link ChannelResult.Status#UNKNOWN}。
 *
 * <p><b>渠道身份（Feature 028 / FR-001）</b>：每个实现必须声明自己的 {@link #channelCode()}——
 * 它是注册表的 key、也是 {@code payment_attempts.channel_code} 的取值来源。没有身份，渠道就不是
 * 一个真实概念，注册表也无处可挂（ADR-0072 / ADR-0073）。</p>
 */
public interface PaymentChannel {

    /**
     * 渠道身份码：**大写、非空、全局唯一**（ADR-0072 / FR-001）。
     *
     * <p>用途有三：① {@code ChannelRegistry} 的注册 key（注册期校验非空且唯一）；
     * ② 精确寻址已发生的渠道交互（INV-6：退款/重试/主动查询按 {@code payment_attempts.channel_code}
     * 解析渠道，禁止重新路由）；③ 幂等键构造（{@code payment:{orderNo}:{channelCode}:{attemptSeq}}）。</p>
     */
    String channelCode();

    /** 发起扣款并返回明确或未知结果。 */
    ChannelResult charge(ChargeRequest request);

    /**
     * 发起退款并返回明确或未知结果。
     *
     * <p>与扣款一致，超时/断连/不完整响应必须映射为 {@link ChannelResult.Status#UNKNOWN}。</p>
     */
    ChannelResult refund(RefundRequest request);

    /**
     * 主动查询渠道侧支付状态，用于把 UNKNOWN 收敛为权威终态（spec US2 / ADR-0003）。
     *
     * <p>渠道在结果仍不明确时必须返回 {@link ChannelResult.Status#UNKNOWN}（从不臆断成败）；
     * 返回 SUCCESS/FAILURE 视为权威结果，由收敛服务据此推进。</p>
     */
    ChannelResult queryStatus(QueryStatusRequest request);
}
