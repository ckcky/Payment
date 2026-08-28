package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import org.springframework.stereotype.Component;

/**
 * 共享的「加载支付与当前尝试 → 应用渠道结果 → 持久化 → 按需触发一次履约 RPC」编排。
 * 回调（{@link PaymentCallbackService}）与未知收敛（{@link PaymentUnknownResolutionService}）复用，
 * 保证履约只在支付真正迁移为成功时触发一次。
 */
@Component
public class PaymentResultProcessor {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final FulfillmentGateway fulfillmentGateway;
    private final OrderGateway orderGateway;

    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  FulfillmentGateway fulfillmentGateway,
                                  OrderGateway orderGateway) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.fulfillmentGateway = fulfillmentGateway;
        this.orderGateway = orderGateway;
    }

    /** 返回支付是否真正发生了状态迁移（据此决定是否已触发履约 RPC）。 */
    public boolean applyAndNotify(Long paymentId, ChannelResult result) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        PaymentAttempt attempt = attemptRepository.findById(payment.getCurrentAttemptId())
                .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR,
                        "payment attempt missing: " + payment.getCurrentAttemptId()));
        boolean changed = PaymentResultApplier.apply(payment, attempt, result);
        paymentRepository.save(payment);
        attemptRepository.save(attempt);
        if (changed && result.status() == ChannelResult.Status.SUCCESS) {
            PaymentSucceededRequest request = PaymentResultApplier.toSucceededRequest(payment);
            try {
                fulfillmentGateway.notifyPaymentSucceeded(request);
            } catch (RuntimeException ignored) {
                // 履约 RPC 失败不得回滚支付成功事实（跨服务一致性由幂等 + 后续对账收敛）。
            }
            try {
                orderGateway.notifyPaymentSucceeded(request);
            } catch (RuntimeException ignored) {
                // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）。
            }
        }
        return changed;
    }
}
