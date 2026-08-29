package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private final PaymentPersistence paymentPersistence;
    private final PaymentRetryService retryService;
    private final LedgerPostingGateway ledgerGateway;
    private final FulfillmentGateway fulfillmentGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @Autowired
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     LedgerPostingGateway ledgerGateway,
                                     FulfillmentGateway fulfillmentGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this.paymentRepository = paymentRepository;
        this.paymentPersistence = paymentPersistence;
        this.retryService = retryService;
        this.ledgerGateway = ledgerGateway;
        this.fulfillmentGateway = fulfillmentGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /** 兼容构造：不接账本时使用空记账网关（测试/账本未接入场景）。 */
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     FulfillmentGateway fulfillmentGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this(paymentRepository, paymentPersistence, retryService,
                (key, paymentId, amountMinor, feeMinor, currencyCode) -> {
                }, fulfillmentGateway, metrics, auditLogger);
    }

    /**
     * 支付意图创建：幂等受理、创建支付与尝试、调用渠道并应用结果。
     *
     * <p>幂等以数据库唯一约束 {@code uk_payments_idempotency_key} 兜底（非进程内内存登记）：
     * 先按幂等键回查，未命中则插入；并发/重启后的重复插入撞唯一约束，捕获后回查返回首次结果。
     * 持久化（插入待处理 / 应用渠道结果落库）各自为独立短事务（见 {@link PaymentPersistence}），
     * 而外部渠道调用 {@code channel.charge} 与跨服务履约 RPC 均运行在事务之外，
     * 避免 DB 连接被网络调用长期占用（雪崩风险）。履约 RPC 失败不回滚支付成功事实。</p>
     */
    public Payment createPaymentIntent(CreatePaymentCommand cmd) {
        PaymentPersistence.PendingPayment pending = paymentPersistence.insertPending(cmd);
        if (!pending.created()) {
            metrics.counter("payment.duplicate", 1.0, "module", MODULE);
            return pending.payment();
        }
        metrics.counter("payment.created", 1.0, "module", MODULE);

        // 渠道扣款在事务之外执行；通信失败在本次请求内联退避重放（ADR-0012/0013 修订），
        // 重试期间不落库，最终结果与重试次数一次性写入。
        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(
                new ChargeRequest(pending.payment().getId(),
                        pending.payment().getCurrentAttemptId(), cmd.amountMinor(), cmd.currencyCode(),
                        cmd.channelCode()));
        ChannelResult result = outcome.result();

        // 应用渠道结果并落库（独立短事务，含本次实际重试次数）
        PaymentPersistence.AppliedPayment applied = paymentPersistence.applyAndPersist(
                pending.payment().getId(), pending.payment().getCurrentAttemptId(), result, outcome.retries());
        if (applied.changed()) {
            recordTransition(applied.payment(), applied.fromStatus(), result);
        }

        // 跨服务履约 RPC 同样在事务之外执行
        if (applied.changed() && result.status() == ChannelResult.Status.SUCCESS) {
            try {
                fulfillmentGateway.notifyPaymentSucceeded(
                        PaymentResultApplier.toSucceededRequest(applied.payment()));
            } catch (RuntimeException ignored) {
                // 履约 RPC 失败不得回滚支付成功事实（跨服务一致性由幂等 + 后续对账收敛）。
            }
            // 已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）；
            // 记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009，手续费 MVP 计 0）。
            ledgerGateway.postPaymentCapture(applied.payment().getIdempotencyKey(),
                    applied.payment().getId(), applied.payment().getAmountMinor(), 0L,
                    applied.payment().getCurrencyCode());
        }
        return applied.payment();
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
