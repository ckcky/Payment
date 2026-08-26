package com.payment.payment.application.channel;

/**
 * 支付渠道抽象（T035）：核心领域（Payment）不依赖具体渠道实现，只依赖本端口。
 * 每个渠道实现必须把超时/断连/不完整响应映射为 {@link ChannelResult.Status#UNKNOWN}。
 */
public interface PaymentChannel {

    /** 发起扣款并返回明确或未知结果。 */
    ChannelResult charge(ChargeRequest request);
}
