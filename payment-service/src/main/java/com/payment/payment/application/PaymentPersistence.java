package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付持久化的事务边界（P0-3）：把「插入支付」「应用渠道结果并落库」收缩为各自的短事务，
 * 使 {@code PaymentApplicationService} 中的外部渠道调用与跨服务 RPC 运行在事务之外，
 * 避免 DB 连接被网络调用长期占用（雪崩风险）。
 *
 * <p>幂等键以数据库唯一约束兜底：{@code insertPending} 先回查，未命中再插入；并发/重启后
 * 的重复插入撞唯一约束时捕获并回查返回首次结果（不重复入账）。</p>
 */
@Component
public class PaymentPersistence {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;

    public PaymentPersistence(PaymentRepository paymentRepository,
                             PaymentAttemptRepository attemptRepository) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
    }

    /** 插入待处理支付；若幂等键已存在则返回既有支付（created=false，表示命中重复）。 */
    @Transactional
    public PendingPayment insertPending(CreatePaymentCommand cmd) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return new PendingPayment(existing.get(), false);
        }
        Payment payment = new Payment(cmd.transactionId(), cmd.orderId(), cmd.userId(),
                cmd.amountMinor(), cmd.currencyCode(), cmd.idempotencyKey());
        payment = insertNew(payment);
        PaymentAttempt attempt = new PaymentAttempt(payment.getId(), cmd.channelCode(), 0);
        attempt = attemptRepository.save(attempt);
        payment.start(attempt.getId());
        payment = paymentRepository.save(payment);
        return new PendingPayment(payment, true);
    }

    /** 把权威渠道结果应用到支付与尝试状态机并落库（独立短事务）。 */
    @Transactional
    public AppliedPayment applyAndPersist(Long paymentId, Long attemptId, ChannelResult result) {
        return applyAndPersist(paymentId, attemptId, result, 0);
    }

    /**
     * 同上，并落库本次渠道调用实际发生的重试次数（ADR-0013 修订）。
     *
     * <p>重试在请求内联完成、期间不写库；{@code retries} 在最终收敛时随本次写入一并落库，
     * 保证「这次支付重放了几轮」可观测，同时不引入每次重试一条写。</p>
     */
    @Transactional
    public AppliedPayment applyAndPersist(Long paymentId, Long attemptId, ChannelResult result, int retries) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "attempt not found: " + attemptId));
        PaymentStatus fromStatus = payment.getStatus();
        boolean changed = PaymentResultApplier.apply(payment, attempt, result);
        for (int i = 0; i < retries; i++) {
            attempt.recordRetry();
        }
        paymentRepository.save(payment);
        attemptRepository.save(attempt);
        return new AppliedPayment(payment, fromStatus, changed);
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

    /** {@code insertPending} 的返回：支付聚合与是否 newly created。 */
    public record PendingPayment(Payment payment, boolean created) {
    }

    /** {@code applyAndPersist} 的返回：应用后的支付、迁移前状态、是否发生真正状态迁移。 */
    public record AppliedPayment(Payment payment, PaymentStatus fromStatus, boolean changed) {
    }
}
