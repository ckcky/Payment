package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付意图创建（T037）：幂等受理、创建支付与尝试、调用渠道并应用结果。
 *
 * <p>幂等键以 {@code payment:create} 作用域登记：先于扣款登记（避免并发重复重复扣款），
 * 重复请求返回首次结果。</p>
 */
@Service
public class PaymentApplicationService {

    private static final String MODULE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentChannel channel;
    private final FulfillmentGateway fulfillmentGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentAttemptRepository attemptRepository,
                                     PaymentChannel channel,
                                     FulfillmentGateway fulfillmentGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.channel = channel;
        this.fulfillmentGateway = fulfillmentGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 支付意图创建：幂等受理、创建支付与尝试、调用渠道并应用结果。
     *
     * <p>幂等以数据库唯一约束 {@code uk_payments_idempotency_key} 兜底（非进程内内存登记）：
     * 先按幂等键回查，未命中则插入；并发/重启后的重复插入撞唯一约束，捕获后回查返回首次结果。
     * 本地多步写（支付 + 尝试）在同一本地事务内原子提交；履约 RPC 失败不回滚支付成功事实。</p>
     */
    @Transactional
    public Payment createPaymentIntent(CreatePaymentCommand cmd) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            metrics.counter("payment.duplicate", 1.0, "module", MODULE);
            return existing.get();
        }

        Payment payment = new Payment(cmd.transactionId(), cmd.orderId(), cmd.userId(),
                cmd.amountMinor(), cmd.currencyCode(), cmd.idempotencyKey());
        payment = insertNew(payment);
        metrics.counter("payment.created", 1.0, "module", MODULE);

        PaymentAttempt attempt = new PaymentAttempt(payment.getId(), cmd.channelCode(), 0);
        attempt = attemptRepository.save(attempt);
        payment.start(attempt.getId());

        ChannelResult result = channel.charge(new ChargeRequest(payment.getId(), attempt.getId(),
                cmd.amountMinor(), cmd.currencyCode(), cmd.channelCode()));
        PaymentStatus fromStatus = payment.getStatus();
        boolean changed = PaymentResultApplier.apply(payment, attempt, result);
        paymentRepository.save(payment);
        attemptRepository.save(attempt);
        if (changed) {
            recordTransition(payment, fromStatus, result);
        }
        if (changed && result.status() == ChannelResult.Status.SUCCESS) {
            try {
                fulfillmentGateway.notifyPaymentSucceeded(PaymentResultApplier.toSucceededRequest(payment));
            } catch (RuntimeException ignored) {
                // 履约 RPC 失败不得回滚支付成功事实（跨服务一致性由幂等 + 后续对账收敛）。
            }
        }
        return payment;
    }

    /** 插入新支付；并发/重启后撞幂等键唯一约束时，回查并返回首次结果（不重复入账）。 */
    private Payment insertNew(Payment payment) {
        try {
            return paymentRepository.save(payment);
        } catch (DuplicateKeyException e) {
            return paymentRepository.findByIdempotencyKey(payment.getIdempotencyKey())
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "payment duplicate: " + payment.getIdempotencyKey()));
        }
    }

    public Payment getPayment(Long id) {
        return requirePayment(id);
    }

    private Payment requirePayment(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + id));
    }

    /** 支付真正迁移到终态/未知后记录业务指标与资金审计（fire-and-forget，不改变控制流）。 */
    private void recordTransition(Payment payment, PaymentStatus fromStatus, ChannelResult result) {
        String action = switch (result.status()) {
            case SUCCESS -> "payment.succeeded";
            case FAILURE -> "payment.failed";
            case UNKNOWN -> "payment.unknown";
        };
        PaymentStatus toStatus = switch (result.status()) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
        };
        metrics.counter(action, 1.0, "module", MODULE);
        auditLogger.audit(action, payment.getIdempotencyKey(), payment.getAmountMinor(),
                payment.getCurrencyCode(), fromStatus.name(), toStatus.name(), "payment",
                String.valueOf(payment.getId()));
    }
}
