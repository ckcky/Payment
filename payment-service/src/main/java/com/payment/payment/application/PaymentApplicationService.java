package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
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
    private final PaymentChannel channel;
    private final PaymentRetryService retryService;
    private final FulfillmentGateway fulfillmentGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentChannel channel,
                                     PaymentRetryService retryService,
                                     FulfillmentGateway fulfillmentGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this.paymentRepository = paymentRepository;
        this.paymentPersistence = paymentPersistence;
        this.channel = channel;
        this.retryService = retryService;
        this.fulfillmentGateway = fulfillmentGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
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

        // 渠道扣款在事务之外执行
        ChannelResult result = channel.charge(new ChargeRequest(pending.payment().getId(),
                pending.payment().getCurrentAttemptId(), cmd.amountMinor(), cmd.currencyCode(),
                cmd.channelCode()));

        // 瞬时失败且未达重试上限 → 安排退避重试，支付保持 PROCESSING（spec US3 / FR-005）
        Payment retryHandled = retryService.tryHandleRetryable(
                pending.payment().getId(), pending.payment().getCurrentAttemptId(), result);
        if (retryHandled != null) {
            return retryHandled;
        }

        // 应用渠道结果并落库（独立短事务）
        PaymentPersistence.AppliedPayment applied = paymentPersistence.applyAndPersist(
                pending.payment().getId(), pending.payment().getCurrentAttemptId(), result);
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
