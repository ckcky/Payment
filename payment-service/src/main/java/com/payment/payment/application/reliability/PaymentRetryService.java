package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 请求内联重试（spec US3 / FR-005~FR-007 / ADR-0012~0014 修订版）。
 *
 * <p>规则：
 * <ul>
 *   <li><b>可重试性只看通信响应码</b>（{@link ChannelResult#retryable()}）：
 *       {@code TransportCode != SUCCESS} 即重试，<b>超时算通信失败</b>（含 {@code TIMEOUT}）。</li>
 *   <li>业务响应码非 SUCCESS（通信成功但被拒）→ <b>不重试</b>，直接进 {@code FAILED}（FR-006）。</li>
 *   <li>重试在<b>本次请求线程内</b>同步退避重放，<b>不落库、不进调度队列</b>（ADR-0013 修订）。</li>
 *   <li>重试在<b>同一 attempt</b> 上重放（幂等键不变），不创建新 attempt（ADR-0014）。</li>
 *   <li>重试耗尽且结果仍不确定 → 结果保持 {@code UNKNOWN}、reason 记为
 *       {@value #EXHAUSTED_REASON}，发 {@code payment.retry_exhausted} 后由 US2 主动查询收敛，
 *       <b>绝不臆断成败</b>（FR-007）。</li>
 * </ul>
 * 已进入 UNKNOWN 的支付不在本类重试——那属于主动查询收敛（{@code ChannelQueryService}）的职责。</p>
 */
@Service
public class PaymentRetryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryService.class);

    private static final String MODULE = "payment";
    public static final String EXHAUSTED_REASON = "RETRY_EXHAUSTED";

    private final PaymentChannel channel;
    private final ReliabilityConfig config;
    private final BusinessMetrics metrics;

    public PaymentRetryService(PaymentChannel channel, ReliabilityConfig config, BusinessMetrics metrics) {
        this.channel = channel;
        this.config = config;
        this.metrics = metrics;
    }

    /**
     * 调用渠道并在通信失败时内联重放，返回最终结果与本次实际重试次数。
     *
     * <p>重试期间不写数据库；调用方在最终收敛时把 {@code retries} 一并落库（单次写）。</p>
     */
    public RetryOutcome chargeWithRetry(ChargeRequest request) {
        ChannelResult result = channel.charge(request);
        int retries = 0;
        while (result.retryable() && (retries + 1) < config.getRetryMaxAttempts()) {
            Duration backoff = backoffFor(retries);
            log.warn("渠道通信失败（{}），第 {} 次重试，退避 {}ms: {}",
                    result.transportCode(), retries + 1, backoff.toMillis(), result.reason());
            sleep(backoff);
            metrics.counter("payment.retry", 1.0, "module", MODULE);
            result = channel.charge(request); // 同一 attempt 重放，幂等键不变（ADR-0014）
            retries++;
        }
        if (result.retryable()) {
            metrics.counter("payment.retry_exhausted", 1.0, "module", MODULE);
            log.warn("渠道通信失败重试耗尽（{} 次调用，上限 {}），支付进 UNKNOWN 待主动查询收敛: {}",
                    retries + 1, config.getRetryMaxAttempts(), result.transportCode());
            return new RetryOutcome(result.withReason(EXHAUSTED_REASON), retries);
        }
        return new RetryOutcome(result, retries);
    }

    /** 一次渠道调用的最终结果与实际重试次数（重试次数由调用方在收敛时落库）。 */
    public record RetryOutcome(ChannelResult result, int retries) {
    }

    private void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry interrupted", e);
        }
    }

    /** 第 {@code retryIndex} 次重试的退避时长（越界取序列最后一个）。 */
    private Duration backoffFor(int retryIndex) {
        List<Duration> backoff = config.getRetryBackoff();
        if (backoff == null || backoff.isEmpty()) {
            return Duration.ofSeconds(1);
        }
        return backoff.get(Math.min(retryIndex, backoff.size() - 1));
    }
}
