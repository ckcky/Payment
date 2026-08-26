package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.idempotency.IdempotencyKey;
import com.payment.common.core.idempotency.IdempotencyRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 支付意图创建（T037）：幂等受理、创建支付与尝试、调用渠道并应用结果。
 *
 * <p>幂等键以 {@code payment:create} 作用域登记：先于扣款登记（避免并发重复重复扣款），
 * 重复请求返回首次结果。</p>
 */
@Service
public class PaymentApplicationService {

    private static final String IDEMPOTENCY_SCOPE = "payment:create";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentChannel channel;
    private final IdempotencyRegistry idempotencyRegistry;
    private final FulfillmentGateway fulfillmentGateway;

    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentAttemptRepository attemptRepository,
                                     PaymentChannel channel,
                                     IdempotencyRegistry idempotencyRegistry,
                                     FulfillmentGateway fulfillmentGateway) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.channel = channel;
        this.idempotencyRegistry = idempotencyRegistry;
        this.fulfillmentGateway = fulfillmentGateway;
    }

    public Payment createPaymentIntent(CreatePaymentCommand cmd) {
        IdempotencyKey key = IdempotencyKey.of(IDEMPOTENCY_SCOPE, cmd.idempotencyKey());
        Optional<String> existing = idempotencyRegistry.find(key);
        if (existing.isPresent()) {
            return requirePayment(Long.valueOf(existing.get()));
        }

        Payment payment = new Payment(cmd.transactionId(), cmd.orderId(), cmd.userId(),
                cmd.amountMinor(), cmd.currencyCode(), cmd.idempotencyKey());
        payment = paymentRepository.save(payment);

        // 先登记幂等键再扣款，避免并发重复扣款；登记失败则丢弃本地未扣款支付，返回胜者。
        if (!idempotencyRegistry.recordIfAbsent(key, String.valueOf(payment.getId()))) {
            String winnerId = idempotencyRegistry.find(key)
                    .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR, "idempotency race"));
            return requirePayment(Long.valueOf(winnerId));
        }

        PaymentAttempt attempt = new PaymentAttempt(payment.getId(), cmd.channelCode(), 0);
        attempt = attemptRepository.save(attempt);
        payment.start(attempt.getId());

        ChannelResult result = channel.charge(new ChargeRequest(payment.getId(), attempt.getId(),
                cmd.amountMinor(), cmd.currencyCode(), cmd.channelCode()));
        boolean changed = PaymentResultApplier.apply(payment, attempt, result);
        paymentRepository.save(payment);
        attemptRepository.save(attempt);
        if (changed && result.status() == ChannelResult.Status.SUCCESS) {
            fulfillmentGateway.notifyPaymentSucceeded(PaymentResultApplier.toSucceededRequest(payment));
        }
        return payment;
    }

    public Payment getPayment(Long id) {
        return requirePayment(id);
    }

    private Payment requirePayment(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + id));
    }
}
