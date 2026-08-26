package com.payment.payment.application;

import com.payment.common.core.ModuleNames;
import com.payment.common.core.event.DomainEvent;
import com.payment.common.dto.event.PaymentFailed;
import com.payment.common.dto.event.PaymentSucceeded;
import com.payment.common.dto.event.PaymentUnknown;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;

/**
 * 把渠道结果应用到支付与尝试状态机，并据此决定是否发布领域事件。
 *
 * <p>返回 {@code true} 表示「支付发生了真正的状态迁移」，调用方据此发布一次事件；
 * 终态冲突/重复回调由状态机吸收（返回 {@code false}），保证「最多一次」业务效果。</p>
 */
final class PaymentResultApplier {

    private PaymentResultApplier() {
    }

    static boolean apply(Payment payment, PaymentAttempt attempt, ChannelResult result) {
        return switch (result.status()) {
            case SUCCESS -> {
                if (result.channelReference() != null) {
                    attempt.accept(result.channelReference());
                }
                attempt.succeed();
                yield payment.succeed();
            }
            case FAILURE -> {
                if (result.channelReference() != null) {
                    attempt.accept(result.channelReference());
                }
                attempt.fail(result.reason());
                yield payment.fail(result.reason());
            }
            case UNKNOWN -> {
                attempt.markUnknown(result.reason());
                yield payment.markUnknown(result.reason());
            }
        };
    }

    static DomainEvent toEvent(Payment payment, ChannelResult result) {
        return switch (result.status()) {
            case SUCCESS -> new PaymentSucceeded(ModuleNames.PAYMENT, String.valueOf(payment.getId()), 1L,
                    payment.getOrderId(), payment.getTransactionId(), payment.getUserId(),
                    payment.getAmountMinor(), payment.getCurrencyCode());
            case FAILURE -> new PaymentFailed(ModuleNames.PAYMENT, String.valueOf(payment.getId()), 1L,
                    payment.getOrderId(), payment.getTransactionId(), payment.getFailureReason());
            case UNKNOWN -> new PaymentUnknown(ModuleNames.PAYMENT, String.valueOf(payment.getId()), 1L,
                    payment.getOrderId(), payment.getTransactionId(), payment.getFailureReason());
        };
    }
}
