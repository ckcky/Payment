package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.payment.domain.Payment;
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
 * <h3>spec 041：本类只写 {@code payments} 表</h3>
 * <p>渠道单（{@code channel_orders}）的开单、收敛、落库全部由渠道网关域的
 * {@code ChannelOrderService} 承担——它是<b>渠道层的订单</b>，不是支付单的附属记录。
 * 改造前本类在同一事务里替渠道层开单、替渠道层 {@code save} 渠道单，
 * 等于把渠道单的生命周期编排写进了资金动作域。</p>
 *
 * <p><b>代价（spec 041 / D2，负责人裁决）</b>：两侧不再共享事务。payment 落库后
 * 渠道单开单失败 ⇒ payment 停在 {@code PENDING}，由主动查询 / 超时扫描兜底；
 * 两侧终态瞬时偏差 ⇒ 由对账收敛。这是用最终一致换模块自治的取舍。</p>
 *
 * <p><b>限额闸门（spec 027 / FR-006、INV-3）</b>：{@code insertPending} 的事务还额外包住
 * {@link LimitGate#acquire} 的「惰性回收 + 三周期预占」。这样超限抛出的
 * {@code LIMIT_EXCEEDED} 会让<b>整个建单事务</b>回滚——{@code payments} 不落行，
 * 是真正的「<b>未创建</b>」而非「创建了再拒」。</p>
 */
@Component
public class PaymentPersistence {

    private final PaymentRepository paymentRepository;
    private final LimitGate limitGate;

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentPersistence(PaymentRepository paymentRepository, LimitGate limitGate) {
        this.paymentRepository = paymentRepository;
        this.limitGate = limitGate;
    }

    /** 兼容构造（测试 / 限额关闭场景）：闸门用恒不放行的空实现。 */
    public PaymentPersistence(PaymentRepository paymentRepository) {
        this(paymentRepository, LimitGate.disabled());
    }

    /**
     * 插入待处理支付；若幂等键已存在则返回既有支付（{@code created=false}，表示命中重复）。
     *
     * <p><b>spec 041</b>：本方法<b>只写 {@code payments}</b>，不开渠道单、不推进状态机——
     * 支付单以 {@code PENDING} 落库，等渠道单开好后由 {@link #attachChannelOrder} 推进到
     * {@code PROCESSING}。渠道单的开立是 {@code ChannelOrderService} 的职责。</p>
     */
    @Transactional
    public PendingPayment insertPending(CreatePaymentCommand cmd, String routedChannelCode) {
        // Feature 015（ADR-015）混合幂等策略，修复「第二笔成功撞同一幂等键静默少记账」(C2)：
        // 1) 调用方显式传 idempotencyKey → 沿用既有 T018 契约（同 key 重试返回同一支付单）；
        // 2) 未传（order-service 显式选渠道，每次新建支付单）→ 服务端生成
        //    payment:{orderNo}:{channelCode}:{attemptSeq}，attemptSeq 取同交易已存在支付单数 + 1，
        //    保证换渠道/重试新建支付单时幂等键唯一，账本按支付单维度记账不丢笔。
        //
        // FR-028（Feature 028）：{@code channelCode} MUST 用**路由后**的最终渠道码，而非调用方原始
        // （可能为 null）值——否则会出现 payment:OR1:null:1 这类脏键。
        String idempotencyKey = cmd.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            int attemptSeq = (int) paymentRepository.countByTransactionId(cmd.transactionId()) + 1;
            idempotencyKey = "payment:" + cmd.orderNo() + ":" + routedChannelCode + ":" + attemptSeq;
        }
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            // 幂等命中：回放既有支付单（不改写、不新建、不开第二条渠道单）。
            //
            // 注意这里**不做限额闸门**：命中的是同一笔已受理的支付（同 idempotencyKey），
            // 它在上一次请求里已经预占过。再预占一次会重复占用额度。
            return new PendingPayment(existing.get(), false);
        }
        Payment payment = new Payment(cmd.transactionId(), cmd.orderNo(), cmd.userId(),
                cmd.amountMinor(), cmd.currencyCode(), idempotencyKey);
        // spec 031 / §13：商户号是**订单带来的事实**，创建时点写入、之后不可变（记账解析商户应付账户用）
        payment.withMerchantId(cmd.merchantId());
        // 限额闸门（spec 027 / FR-006、INV-3）：在**落库之前**预占，使超限时本事务整体回滚。
        limitGate.acquire(cmd.userId(), cmd.currencyCode(), cmd.amountMinor(), payment.getPaymentNo());
        InsertOutcome outcome = insertNew(payment);
        if (!outcome.newlyCreated()) {
            // 并发/重启后撞 uk_payments_idempotency_key：**必须在此处回放库内值并立即返回**。
            //
            // 修复前（FIX-2，2026-09-20 边界复审）：这里只是回查拿到既有支付单，随后**仍继续**
            // 开第二条渠道单、再 payment.start(新 id)——而既有支付单早已是 PROCESSING，
            // 于是 start 抛 STATE_TRANSITION_VIOLATION、整个建单事务回滚，
            // **幂等重试被答成状态机错误**。
            return new PendingPayment(outcome.payment(), false);
        }
        return new PendingPayment(outcome.payment(), true);
    }

    /**
     * 把渠道单关联回支付单（{@code PENDING → PROCESSING}）。
     *
     * <p><b>spec 041</b>：渠道单由 {@code ChannelOrderService} 开好后，payment 侧在此
     * 记录「本次走的哪一条渠道单」。已不在 {@code PENDING}（例如幂等重放回来）时幂等跳过，
     * 不覆盖既有 {@code currentAttemptId}——换一条渠道单等于换一笔渠道流水，是资金事故。</p>
     *
     * @param channelOrderId 渠道单自增 id（库内关联键；对外身份用 {@code channelNo}）
     */
    @Transactional
    public Payment attachChannelOrder(Long paymentId, Long channelOrderId) {
        Payment payment = requirePayment(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return payment;
        }
        payment.start(channelOrderId);
        return paymentRepository.save(payment);
    }

    /**
     * 把权威渠道结果应用到支付单并落库（独立短事务）。
     *
     * <p><b>spec 041</b>：本方法<b>只写 {@code payments}</b>。渠道单的收敛由
     * {@code ChannelOrderService.converge(orderId, result, retries)} 在渠道侧事务内完成；
     * 调用方 MUST 在调用本方法<b>之前</b>完成渠道单收敛（渠道事实先成形，再推进支付状态）。</p>
     */
    @Transactional
    public AppliedPayment applyAndPersist(Long paymentId, ChannelResult result) {
        Payment payment = requirePayment(paymentId);
        PaymentStatus fromStatus = payment.getStatus();
        boolean changed = PaymentResultApplier.applyPayment(payment, result);
        paymentRepository.save(payment);
        return new AppliedPayment(payment, fromStatus, changed);
    }

    private Payment requirePayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
    }

    /**
     * 插入新支付；并发/重启后撞幂等键唯一约束时回查首次结果（不重复入账）。
     *
     * <p><b>必须回传「是否真的新建」</b>（FIX-2）：调用方据此在重复时<b>立即回放库内值</b>。
     * 只返回 {@code Payment} 无法区分「刚插入的」与「回查到的」。</p>
     *
     * <p><b>不引入 UPSERT</b>：{@code status} / {@code currentAttemptId} / 渠道结果都是
     * <b>不可被后来请求覆盖</b>的字段，{@code ON DUPLICATE KEY UPDATE} 会把这些一并改写，
     * 直接毁掉首次事实。</p>
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

    /** {@code insertNew} 的结果：支付单 + 是否真由本次调用新建。 */
    private record InsertOutcome(Payment payment, boolean newlyCreated) {
    }

    /**
     * {@code insertPending} 的返回：支付聚合与是否 newly created。
     *
     * <p><b>spec 041</b>：不再回带渠道单——渠道单由渠道域自己持有，payment 侧需要渠道码时
     * 经 {@code ChannelOrderService.findChannelOrder(paymentNo)} 查询。</p>
     */
    public record PendingPayment(Payment payment, boolean created) {
    }

    /** {@code applyAndPersist} 的返回：应用后的支付、迁移前状态、是否发生真正状态迁移。 */
    public record AppliedPayment(Payment payment, PaymentStatus fromStatus, boolean changed) {
    }
}
