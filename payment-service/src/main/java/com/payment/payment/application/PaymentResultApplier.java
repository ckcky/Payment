package com.payment.payment.application;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;

/**
 * 把渠道结果应用到支付与尝试状态机，并据此决定是否触发履约 RPC。
 *
 * <p>返回 {@code true} 表示「支付发生了真正的状态迁移」，调用方据此触发一次履约 RPC；
 * 终态冲突/重复回调由状态机吸收（返回 {@code false}），保证「最多一次」业务效果。</p>
 */
final class PaymentResultApplier {

    private PaymentResultApplier() {
    }

    static boolean apply(Payment payment, PaymentAttempt attempt, ChannelResult result) {
        // 错误分类由双响应码派生后落库，供观测排障（ADR-0012）；不参与重试判定。
        attempt.setErrorType(result.errorType());
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

    static PaymentSucceededRequest toSucceededRequest(Payment payment) {
        return new PaymentSucceededRequest(payment.getPaymentNo(), payment.getOrderNo(),
                payment.getTransactionId(), payment.getUserId(),
                payment.getAmountMinor(), payment.getCurrencyCode());
    }
}
