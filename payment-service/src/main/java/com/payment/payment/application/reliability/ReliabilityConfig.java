package com.payment.payment.application.reliability;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付可靠性配置（plan T002 / ADR-0003~0005）。
 *
 * <p>所有阈值均可配置覆盖；未配置时取下列默认值（spec Assumptions）。</p>
 */
@Component
@ConfigurationProperties(prefix = "payment.reliability")
public class ReliabilityConfig {

    /** 支付处于 PROCESSING 超过该时长且无明确结果即判定超时进入 UNKNOWN（ADR-0004）。默认 30s。 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 超时扫描调度间隔（毫秒），由 {@code @Scheduled(fixedDelay)} 使用（ADR-0004）。默认 10000ms。 */
    private long timeoutScanIntervalMs = 10_000;

    /** 幂等调用重试上限（含首次），默认 3（ADR-0005）。 */
    private int retryMaxAttempts = 3;

    /** 重试退避序列，默认 1s / 2s / 4s（ADR-0005）。 */
    private List<Duration> retryBackoff = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    /** UNKNOWN 主动查询尝试上限，达到后停止自动查询（ADR-0003）。默认 5。 */
    private int queryMaxAttempts = 5;

    /** UNKNOWN 主动查询调度间隔（毫秒），由 {@code @Scheduled(fixedDelay)} 使用（ADR-0003）。默认 15000ms。 */
    private long queryIntervalMs = 15_000;

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public long getTimeoutScanIntervalMs() {
        return timeoutScanIntervalMs;
    }

    public void setTimeoutScanIntervalMs(long timeoutScanIntervalMs) {
        this.timeoutScanIntervalMs = timeoutScanIntervalMs;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public List<Duration> getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(List<Duration> retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getQueryMaxAttempts() {
        return queryMaxAttempts;
    }

    public void setQueryMaxAttempts(int queryMaxAttempts) {
        this.queryMaxAttempts = queryMaxAttempts;
    }

    public long getQueryIntervalMs() {
        return queryIntervalMs;
    }

    public void setQueryIntervalMs(long queryIntervalMs) {
        this.queryIntervalMs = queryIntervalMs;
    }
}
