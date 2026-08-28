package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptErrorType;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 有限重试与重试耗尽处理（spec US3 / FR-005~FR-007 / ADR-0005、ADR-0012~0014）。
 *
 * <p>规则（最简实现）：
 * <ul>
 *   <li>仅 {@link PaymentAttemptErrorType#TRANSIENT}（幂等瞬时错误）重试；硬拒绝直接进失败，
 *       渠道不明确直接进 UNKNOWN（不猜成败）。</li>
 *   <li>尝试次数上限含首次（默认 3）；未达上限按退避序列（默认 1s/2s/4s）安排下次重试，
 *       期间支付保持 PROCESSING。</li>
 *   <li>重试耗尽且结果仍不确定 → 支付 {@code markUnknown("RETRY_EXHAUSTED")}，
 *       发 {@code payment.retry_exhausted}（FR-007），不误判为成功或失败。</li>
 * </ul>
 * 重试经进程内调度器驱动，不引入 MQ/2PC（Constitution §IV、ADR-0001）。</p>
 */
@Service
public class PaymentRetryService {

    private static final String MODULE = "payment";
    private static final String EXHAUSTED_REASON = "RETRY_EXHAUSTED";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentChannel channel;
    private final PaymentResultProcessor processor;
    private final ReliabilityConfig config;
    private final BusinessMetrics metrics;

    public PaymentRetryService(PaymentRepository paymentRepository,
                               PaymentAttemptRepository attemptRepository,
                               PaymentChannel channel,
                               PaymentResultProcessor processor,
                               ReliabilityConfig config,
                               BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.channel = channel;
        this.processor = processor;
        this.config = config;
        this.metrics = metrics;
    }

    /**
     * 处理一次渠道结果：可重试则安排退避重试并返回支付；否则返回 {@code null} 交由调用方按既有流程应用结果。
     *
     * @return 已由本方法处理的支付（安排了重试，或因耗尽进入 UNKNOWN）；{@code null} 表示无需重试处理
     */
    public Payment tryHandleRetryable(Long paymentId, Long attemptId, ChannelResult result) {
        if (!isRetryable(result)) {
            return null;
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("payment not found: " + paymentId));
        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("attempt not found: " + attemptId));

        int attemptsMade = attempt.getRetryCount() + 1;
        if (attemptsMade < config.getRetryMaxAttempts()) {
            attempt.setErrorType(PaymentAttemptErrorType.TRANSIENT);
            attempt.setNextRetryAt(Instant.now().plus(backoffFor(attemptsMade - 1)));
            attempt.recordRetry();
            attemptRepository.save(attempt);
            metrics.counter("payment.retry", 1.0, "module", MODULE);
            return payment; // 保持 PROCESSING，等待调度器重放
        }

        // 重试耗尽且结果仍不确定 → UNKNOWN（FR-007），绝不臆断成败
        attempt.setErrorType(PaymentAttemptErrorType.TRANSIENT);
        attempt.setNextRetryAt(null);
        attempt.markUnknown(result.reason());
        attemptRepository.save(attempt);
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            payment.markUnknown(EXHAUSTED_REASON);
            paymentRepository.save(payment);
        }
        metrics.counter("payment.retry_exhausted", 1.0, "module", MODULE);
        return payment;
    }

    /** 执行到期的重试，返回本轮实际重放的尝试数量。 */
    public int retryDue(Instant now) {
        List<PaymentAttempt> due = attemptRepository.findRetryableDue(now);
        int executed = 0;
        for (PaymentAttempt attempt : due) {
            Payment payment = paymentRepository.findById(attempt.getPaymentId()).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.PROCESSING) {
                // 支付已终态/已 UNKNOWN：清理重试计划，避免无效重放
                attempt.setNextRetryAt(null);
                attemptRepository.save(attempt);
                continue;
            }
            ChannelResult result = channel.charge(new ChargeRequest(payment.getId(), attempt.getId(),
                    payment.getAmountMinor(), payment.getCurrencyCode(), attempt.getChannelCode()));
            if (tryHandleRetryable(payment.getId(), attempt.getId(), result) == null) {
                processor.applyAndNotify(payment.getId(), result);
            }
            executed++;
        }
        return executed;
    }

    private boolean isRetryable(ChannelResult result) {
        return result.status() == ChannelResult.Status.FAILURE
                && result.errorType() == PaymentAttemptErrorType.TRANSIENT;
    }

    /** 第 {@code index} 次重试的退避时长（越界取序列最后一个）。 */
    private Duration backoffFor(int index) {
        List<Duration> backoff = config.getRetryBackoff();
        if (backoff == null || backoff.isEmpty()) {
            return Duration.ofSeconds(1);
        }
        return backoff.get(Math.min(index, backoff.size() - 1));
    }
}
