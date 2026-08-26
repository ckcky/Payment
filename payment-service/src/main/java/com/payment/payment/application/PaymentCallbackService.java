package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

/**
 * 支付回调处理（T038）：去重、乱序与延迟保护。
 *
 * <p>重复回调映射到同一渠道引用；终态成功不被后到的失败回调覆盖；只有支付真正迁移时才
 * 发布事件（一次）。</p>
 */
@Service
public class PaymentCallbackService {

    private static final String MODULE = "payment";

    private final PaymentResultProcessor processor;
    private final PaymentRepository paymentRepository;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PaymentCallbackService(PaymentResultProcessor processor,
                                  PaymentRepository paymentRepository,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger) {
        this.processor = processor;
        this.paymentRepository = paymentRepository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /** 处理一次渠道回调；返回支付是否因此发生状态迁移。 */
    public boolean handleCallback(Long paymentId, ChannelResult result) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        PaymentStatus fromStatus = payment.getStatus();
        boolean changed = processor.applyAndNotify(paymentId, result);
        if (changed) {
            recordTransition(payment, fromStatus, result);
        } else {
            metrics.counter("payment.duplicate_callback", 1.0, "module", MODULE);
        }
        return changed;
    }

    /** 支付真正迁移后记录业务指标与资金审计（fire-and-forget，不改变控制流）。 */
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
