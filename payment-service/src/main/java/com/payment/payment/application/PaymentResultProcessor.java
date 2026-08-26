package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.event.DomainEventPublisher;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import org.springframework.stereotype.Component;

/**
 * 共享的「加载支付与当前尝试 → 应用渠道结果 → 持久化 → 按需发布一次事件」编排。
 * 回调（{@link PaymentCallbackService}）与未知收敛（{@link PaymentUnknownResolutionService}）复用，
 * 保证事件只在支付真正迁移时发布一次。
 */
@Component
public class PaymentResultProcessor {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final DomainEventPublisher publisher;

    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  DomainEventPublisher publisher) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.publisher = publisher;
    }

    /** 返回支付是否真正发生了状态迁移（据此决定是否已发布事件）。 */
    public boolean applyAndPublish(Long paymentId, ChannelResult result) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        PaymentAttempt attempt = attemptRepository.findById(payment.getCurrentAttemptId())
                .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR,
                        "payment attempt missing: " + payment.getCurrentAttemptId()));
        boolean changed = PaymentResultApplier.apply(payment, attempt, result);
        paymentRepository.save(payment);
        attemptRepository.save(attempt);
        if (changed) {
            publisher.publish(PaymentResultApplier.toEvent(payment, result));
        }
        return changed;
    }
}
