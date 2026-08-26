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

    public boolean resolve(Long paymentId, ChannelResult authoritativeResult) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return false;
        }
        boolean changed = processor.applyAndNotify(paymentId, authoritativeResult);
        if (changed) {
            recordTransition(payment, PaymentStatus.UNKNOWN, authoritativeResult);
            // Payment 领域聚合未携带 updatedAt（审计时间戳只落在持久化实体 BaseEntity 上，
            // 新增领域字段属 §8 边界），故 UNKNOWN 收敛耗时以零时长记录，仅保留维度事实。
            metrics.timer("payment.unknown.duration", Duration.ZERO, "module", MODULE);
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
