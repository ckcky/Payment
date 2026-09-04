package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 未知支付收敛（T039）：用查询/权威回调把 UNKNOWN 收敛为成功或失败，且只触发一次履约 RPC。
 *
 * <p>只有处于 {@link PaymentStatus#UNKNOWN} 的支付才被收敛；已终态视为幂等重复，
 * 不重复发布事件（履约只触发一次）。</p>
 */
@Service
public class PaymentUnknownResolutionService {

    private static final String MODULE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentResultProcessor processor;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PaymentUnknownResolutionService(PaymentRepository paymentRepository,
                                           PaymentResultProcessor processor,
                                           BusinessMetrics metrics,
                                           StructuredAuditLogger auditLogger) {
        this.paymentRepository = paymentRepository;
        this.processor = processor;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    public boolean resolve(String ref, ChannelResult authoritativeResult) {
        Long paymentId = resolveId(ref);
        return resolveById(paymentId, authoritativeResult);
    }

    /** 供控制器按「数值 id 或 paymentNo」寻址；内部实现仍以数值 id 走原路径。 */
    private Long resolveId(String ref) {
        if (ref.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(ref);
        }
        return paymentRepository.findByPaymentNo(ref)
                .map(Payment::getId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + ref));
    }

    public boolean resolveById(Long paymentId, ChannelResult authoritativeResult) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return false;
        }
        boolean changed = processor.applyAndNotify(paymentRepository.findById(paymentId)
                .map(Payment::getPaymentNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId)),
                authoritativeResult);
        if (changed) {
            recordTransition(payment, PaymentStatus.UNKNOWN, authoritativeResult);
            // UNKNOWN 真实收敛时长：进入 UNKNOWN 时由状态机记录 enteredUnknownAt（spec US5 / ADR-0015）。
            Instant enteredAt = payment.getEnteredUnknownAt();
            Duration duration = enteredAt == null ? Duration.ZERO : Duration.between(enteredAt, Instant.now());
            metrics.timer("payment.unknown.duration", duration, "module", MODULE);
        }
        return changed;
    }

    /** 支付真正收敛后记录业务指标与资金审计（fire-and-forget，不改变控制流）。 */
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
