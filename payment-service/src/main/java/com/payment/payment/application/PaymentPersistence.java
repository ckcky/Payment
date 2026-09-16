package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelAttemptRecorder;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
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
 *
 * <p><b>两层职责（Feature 028 / FR-003，ADR-0072 / INV-5）</b>：本类只直接写 {@code payments} 表
 * （payment 层）；{@code payment_attempts} 表的创建与收敛经 {@link ChannelAttemptRecorder} 端口
 * 委托给渠道层。<b>这是职责分层而非拆事务</b>——两者仍在同一个 {@code @Transactional} 内，
 * 保证两张表的状态同时迁移（否则会出现 {@code payment=SUCCEEDED / attempt=PENDING} 的永久不一致）。</p>
 */
@Component
public class PaymentPersistence {

    private final PaymentRepository paymentRepository;
    private final ChannelAttemptRecorder attemptRecorder;

    public PaymentPersistence(PaymentRepository paymentRepository,
                             ChannelAttemptRecorder attemptRecorder) {
        this.paymentRepository = paymentRepository;
        this.attemptRecorder = attemptRecorder;
    }

    /** 插入待处理支付；若幂等键已存在则返回既有支付（created=false，表示命中重复）。 */
    @Transactional
    public PendingPayment insertPending(CreatePaymentCommand cmd, String routedChannelCode) {
        // Feature 015（ADR-015）混合幂等策略，修复「第二笔成功撞同一幂等键静默少记账」(C2)：
        // 1) 调用方显式传 idempotencyKey → 沿用既有 T018 契约（同 key 重试返回同一支付单）；
        // 2) 未传（order-service 显式选渠道，每次新建支付单）→ 服务端生成
        //    payment:{orderNo}:{channelCode}:{attemptSeq}，attemptSeq 取同交易已存在支付单数 + 1，
        //    保证换渠道/重试新建支付单时幂等键唯一，账本按支付单维度记账不丢笔。
        //
        // FR-028（Feature 028）：{@code channelCode} MUST 用**路由后**的最终渠道码，而非调用方原始
        // （可能为 null）值——否则会出现 payment:OR1:null:1 这类脏键，且与 D1 的契约放宽叠加。
        String idempotencyKey = cmd.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            int attemptSeq = (int) paymentRepository.countByTransactionId(cmd.transactionId()) + 1;
            idempotencyKey = "payment:" + cmd.orderNo() + ":" + routedChannelCode + ":" + attemptSeq;
        }
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            // 幂等命中：回放既有支付单与它已记录的 PAYMENT attempt（不改写、不新建）
            return new PendingPayment(existing.get(), findPaymentAttempt(existing.get()), false);
        }
        Payment payment = new Payment(cmd.transactionId(), cmd.orderNo(), cmd.userId(),
                cmd.amountMinor(), cmd.currencyCode(), idempotencyKey);
        payment = insertNew(payment);
        // 渠道层写 payment_attempts（INV-5）：路由后的最终渠道码落 attempt 行
        PaymentAttempt attempt = attemptRecorder.openPaymentAttempt(
                payment.getPaymentNo(), routedChannelCode,
                payment.getAmountMinor(), payment.getCurrencyCode());
        payment.start(attempt.getId());
        payment = paymentRepository.save(payment);
        return new PendingPayment(payment, attempt, true);
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
     *
     * <p><b>顺序（plan §B4 风险点）</b>：先收敛 attempt（渠道层）、再推进 payment（payment 层）
     * ——与拆分前的 {@code PaymentResultApplier.apply()} 内联顺序完全一致，保证乐观锁版本号与
     * 幂等重放断言不变（SC-012 零回归）。</p>
     */
    @Transactional
    public AppliedPayment applyAndPersist(Long paymentId, Long attemptId, ChannelResult result, int retries) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        PaymentAttempt attempt = attemptRecorder.require(attemptId);
        PaymentStatus fromStatus = payment.getStatus();
        // ① 渠道侧收敛 attempt（渠道层职责）
        attemptRecorder.converge(attempt, result);
        for (int i = 0; i < retries; i++) {
            attempt.recordRetry();
        }
        // ② 支付侧推进 payment（payment 层职责）
        boolean changed = PaymentResultApplier.applyPayment(payment, result);
        // ③ 两层在同一本地事务内落库
        paymentRepository.save(payment);
        attemptRecorder.save(attempt);
        return new AppliedPayment(payment, attempt, fromStatus, changed);
    }

    /** 按支付单号取它已记录的 PAYMENT attempt（幂等回放路径用；缺记录抛 INTERNAL_ERROR）。 */
    private PaymentAttempt findPaymentAttempt(Payment payment) {
        return attemptRecorder.findPaymentAttempt(payment.getPaymentNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR,
                        "payment exists without a PAYMENT attempt: " + payment.getPaymentNo()));
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

    /**
     * {@code insertPending} 的返回：支付聚合、首次渠道尝试与是否 newly created。
     *
     * <p>{@code attempt} 供调用方在渠道调用前拿到 attemptId（defer 路径与同步路径都要）。</p>
     */
    public record PendingPayment(Payment payment, PaymentAttempt attempt, boolean created) {
    }

    /** {@code applyAndPersist} 的返回：应用后的支付、尝试、迁移前状态、是否发生真正状态迁移。 */
    public record AppliedPayment(Payment payment, PaymentAttempt attempt,
                                 PaymentStatus fromStatus, boolean changed) {
    }
}
