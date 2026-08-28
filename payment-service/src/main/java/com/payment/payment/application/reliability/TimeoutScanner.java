package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 超时收敛（ADR-0004 / spec US1）：扫描处于 PROCESSING 且当前尝试发起时间超过阈值（默认 30s）的支付，
 * 统一推进为 UNKNOWN（原因 TIMEOUT）。
 *
 * <p>不猜成败、绝不触发成功回写（沿用 002 边界 / FR-001/FR-002）：订单保持待支付、交易保持可收敛状态。</p>
 *
 * <p>幂等与「最多一次」：仅 {@code findByStatus(PROCESSING)} 的支付被考量，已终态 / 已 UNKNOWN 自然排除；
 * 迁移经支付状态机唯一入口 {@link Payment#markUnknown(String)}，并发冲突由仓储乐观锁保护。</p>
 */
@Component
public class TimeoutScanner {

    private static final String MODULE = "payment";
    private static final String TIMEOUT_REASON = "TIMEOUT";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final BusinessMetrics metrics;
    private final ReliabilityConfig config;

    public TimeoutScanner(PaymentRepository paymentRepository,
                          PaymentAttemptRepository attemptRepository,
                          BusinessMetrics metrics,
                          ReliabilityConfig config) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.metrics = metrics;
        this.config = config;
    }

    /** 扫描一次，返回本次实际收敛为 UNKNOWN 的支付数量。 */
    public int scan(Instant now) {
        Instant threshold = now.minus(config.getTimeout());
        int timedOut = 0;
        for (Payment payment : paymentRepository.findByStatus(PaymentStatus.PROCESSING)) {
            if (payment.getCurrentAttemptId() == null) {
                continue;
            }
            PaymentAttempt attempt = attemptRepository.findById(payment.getCurrentAttemptId()).orElse(null);
            if (attempt == null) {
                continue;
            }
            if (attempt.getRequestedAt().isBefore(threshold) && payment.markUnknown(TIMEOUT_REASON)) {
                paymentRepository.save(payment);
                metrics.counter("payment.timeout", 1.0, "module", MODULE);
                timedOut++;
            }
        }
        return timedOut;
    }
}
