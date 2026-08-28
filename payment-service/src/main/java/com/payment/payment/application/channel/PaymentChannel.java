package com.payment.payment.application.channel;

/**
 * 支付渠道抽象（T035）：核心领域（Payment）不依赖具体渠道实现，只依赖本端口。
 * 每个渠道实现必须把超时/断连/不完整响应映射为 {@link ChannelResult.Status#UNKNOWN}。
 */
public interface PaymentChannel {

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
