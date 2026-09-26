package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.channelgateway.application.ChannelResult;
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

    /**
     * 用权威结果收敛一笔 UNKNOWN 支付，返回<b>收敛后</b>的支付单（spec 041 / FR-023）。
     *
     * <h3>为什么返回支付单而不是 boolean</h3>
     * <p>改造前本方法返回 {@code boolean}，调用方（Controller）不得不再调一次
     * {@code getPaymentByRef} 回读——「写 + 读」两步编排漏在了 Controller 里。
     * 收敛后回读是收敛流程的一部分，属应用服务职责，故由本方法一并给出。</p>
     *
     * <h3>寻址口径</h3>
     * <p>一律按 {@code paymentNo}（ADR-0063）。改造前的「全数字就当数值主键」双轨猜测已废除
     * （spec 041 / FR-022）：它让调用方靠数据形态猜语义，且实测零调用。</p>
     *
     * @return 收敛后的支付单；非 UNKNOWN（终态或已被收敛）时原样返回，不重复发布事件
     */
    public Payment resolve(String paymentNo, ChannelResult authoritativeResult) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentNo));
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return payment; // 幂等重复：不重复收敛、不重复发布事件
        }
        boolean changed = processor.applyAndNotify(paymentNo, authoritativeResult);
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
        // 收敛可能改变了状态机，返回的必须是**收敛后**的事实（调用方据此渲染响应）。
        return paymentRepository.findByPaymentNo(paymentNo).orElse(payment);
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
