package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.channelgateway.application.ChannelAttemptRecorder;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>限额闸门（spec 027 / FR-006、INV-3）</b>：本方法的事务还额外包住
 * {@link LimitGate#acquire} 的「惰性回收 + 三周期预占」。这样超限抛出的
 * {@code LIMIT_EXCEEDED} 会让<b>整个建单事务</b>回滚——{@code payments} /
 * {@code payment_attempts} 都不落行，是真正的「<b>未创建</b>」而非「创建了再拒」。
 * 闸门放在这里而不是 {@code PaymentApplicationService}，是因为那里没有事务
 * （渠道调用必须在事务外），单独给闸门开事务会让它与建单分属两个边界。</p>
 */
@Component
public class PaymentPersistence {

    private final PaymentRepository paymentRepository;
    private final ChannelAttemptRecorder attemptRecorder;
    private final LimitGate limitGate;

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @Autowired
    public PaymentPersistence(PaymentRepository paymentRepository,
                             ChannelAttemptRecorder attemptRecorder,
                             LimitGate limitGate) {
        this.paymentRepository = paymentRepository;
        this.attemptRecorder = attemptRecorder;
        this.limitGate = limitGate;
    }

    /** 兼容构造（测试 / 限额关闭场景）：闸门用恒不放行的空实现。 */
    public PaymentPersistence(PaymentRepository paymentRepository,
                             ChannelAttemptRecorder attemptRecorder) {
        this(paymentRepository, attemptRecorder, LimitGate.disabled());
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
            // 幂等命中：回放既有支付单与它已记录的 PAYMENT attempt（不改写、不新建）。
            //
            // 注意这里**不做限额闸门**：命中的是同一笔已受理的支付（同 idempotencyKey），
            // 它在上一次请求里已经预占过。再预占一次会重复占用额度（同一 paymentNo 撞
            // UK(biz_no, RESERVE) 会被幂等跳过，但语义上仍不该走这条路）。
            return new PendingPayment(existing.get(), findPaymentAttempt(existing.get()), false);
        }
        Payment payment = new Payment(cmd.transactionId(), cmd.orderNo(), cmd.userId(),
                cmd.amountMinor(), cmd.currencyCode(), idempotencyKey);
        // spec 031 / §13：商户号是**订单带来的事实**，创建时点写入、之后不可变（记账解析商户应付账户用）
        payment.withMerchantId(cmd.merchantId());
        // 限额闸门（spec 027 / FR-006、INV-3）：在**落库之前**预占，使超限时本事务整体回滚。
        // paymentNo 已由 Payment 构造生成（PM+雪花），可直接作为额度流水的 biz_no（ADR-0063）。
        // 注：本调用位于 @Transactional 方法体内，与随后的 insertNew 同事务——
        // 抛 LIMIT_EXCEEDED 时 payments / payment_attempts 都不落行（SC-003）。
        limitGate.acquire(cmd.userId(), cmd.currencyCode(), cmd.amountMinor(), payment.getPaymentNo());
        InsertOutcome outcome = insertNew(payment);
        if (!outcome.newlyCreated()) {
            // 并发/重启后撞 uk_payments_idempotency_key：**必须在此处回放库内值并立即返回**。
            //
            // 修复前（FIX-2，2026-09-20 边界复审）：这里只是回查拿到既有支付单，随后**仍继续**
            // openPaymentAttempt 建第二条 attempt、再 payment.start(新 attemptId)——而既有支付单
            // 早已是 PROCESSING，于是 start 抛 STATE_TRANSITION_VIOLATION、整个建单事务回滚，
            // **幂等重试被答成状态机错误**（调用方拿到 409/500，而不是首次结果）。
            //
            // 正确语义（FR-152 / FR-306）：幂等重复命中已存在支付单时返回**库内**值——
            // 既不再建 attempt（保持 Payment 1:1 PaymentAttempt），
            // 也**不用本次请求的染色值覆盖**库内已记录的模态。
            return new PendingPayment(outcome.payment(), findPaymentAttempt(outcome.payment()), false);
        }
        payment = outcome.payment();
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

    /**
     * 插入新支付；并发/重启后撞幂等键唯一约束时回查首次结果（不重复入账）。
     *
     * <p><b>必须回传「是否真的新建」</b>（FIX-2）：调用方据此在重复时<b>立即回放库内值</b>，
     * 而不是继续为一条已存在的支付单建 attempt。只返回 {@code Payment} 是无法区分
     * 「刚插入的」与「回查到的」的——那正是修复前那条把幂等重试答成状态机错误的路径。</p>
     *
     * <p><b>业务唯一键与 INSERT / duplicate 语义</b>：业务唯一键是
     * {@code uk_payments_idempotency_key}，其取值是<b>确定性</b>的（调用方显式给，或由
     * {@code payment:{orderNo}:{routedChannelCode}:{attemptSeq}} 派生），因此
     * 「先查后插 + 撞键回放」已构成完整语义，<b>不引入 UPSERT</b>：支付单上的
     * {@code status} / {@code currentAttemptId} / 渠道结果都是<b>不可被后来请求覆盖</b>的字段，
     * 换成 {@code ON DUPLICATE KEY UPDATE} 会把这些一并改写，直接毁掉首次事实
     * （与 FR-152 / FR-306「幂等重复返回库内值、不用本次请求染色覆盖」同向）。</p>
     */
    private InsertOutcome insertNew(Payment payment) {
        try {
            return new InsertOutcome(paymentRepository.save(payment), true);
        } catch (DuplicateKeyException e) {
            Payment existing = paymentRepository.findByIdempotencyKey(payment.getIdempotencyKey())
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "payment duplicate: " + payment.getIdempotencyKey()));
            return new InsertOutcome(existing, false);
        }
    }

    /** {@code insertNew} 的结果：支付单 + 是否真由本次调用新建（{@code false} = 撞唯一键后回查到的既有行）。 */
    private record InsertOutcome(Payment payment, boolean newlyCreated) {
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
