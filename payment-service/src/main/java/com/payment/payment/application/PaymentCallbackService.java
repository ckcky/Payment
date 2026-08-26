package com.payment.payment.application;

import com.payment.payment.application.channel.ChannelResult;
import org.springframework.stereotype.Service;

/**
 * 支付回调处理（T038）：去重、乱序与延迟保护。
 *
 * <p>重复回调映射到同一渠道引用；终态成功不被后到的失败回调覆盖；只有支付真正迁移时才
 * 发布事件（一次）。</p>
 */
@Service
public class PaymentCallbackService {

    private final PaymentResultProcessor processor;

    public PaymentCallbackService(PaymentResultProcessor processor) {
        this.processor = processor;
    }

    /** 处理一次渠道回调；返回支付是否因此发生状态迁移。 */
    public boolean handleCallback(Long paymentId, ChannelResult result) {
        return processor.applyAndPublish(paymentId, result);
    }
}
