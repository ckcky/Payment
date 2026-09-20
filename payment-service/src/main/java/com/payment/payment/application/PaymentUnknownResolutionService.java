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
            // spec 030 / FR-257（T67）：UNKNOWN **在途时长**的分桶计数。
            // timer 给分位数、bucket 给「有多少单卡在各档」——后者才是告警能直接用的信号
            // （分位数看不出「有 3 单卡了 40 分钟」这种长尾个例）。
            // **只加指标、不加自动动作**：分级阈值与是否自动处置属 H5 裁决，本 Feature 不做。
            metrics.counter("payment.unknown_age", 1.0, "module", MODULE, "bucket", bucketOf(duration));
        }
        return changed;
    }

    /**
     * UNKNOWN 在途时长的分桶标签（spec 030 / FR-257）。
     *
     * <p>分桶口径（分钟）：{@code <1 / 1-5 / 5-30 / 30-120 / >120}。分档理由：
     * {@code <1} 属正常查询窗口内；{@code >30} 基本可以判定自动查询已无望收敛，
     * 需要人工/对账介入。<b>阈值只影响观测分档，不触发任何自动动作</b>。</p>
     */
    private static String bucketOf(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "lt_1m";
        }
        if (minutes < 5) {
            return "1_5m";
        }
        if (minutes < 30) {
            return "5_30m";
        }
        if (minutes < 120) {
            return "30_120m";
        }
        return "gt_120m";
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
