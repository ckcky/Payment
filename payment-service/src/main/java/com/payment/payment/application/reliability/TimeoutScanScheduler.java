package com.payment.payment.application.reliability;

import java.time.Instant;
import com.payment.common.core.trace.TraceContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时扫描调度器（ADR-0004）：周期性触发 {@link TimeoutScanner#scan(Instant)}，
 * 将长时间停在 PROCESSING 的支付收敛为 UNKNOWN。
 *
 * <p>间隔由 {@code payment.reliability.timeout-scan-interval-ms} 配置（默认 10000ms）。
 * 需 {@code @EnableScheduling}（已在 {@code PaymentApplication} 启用）。</p>
 */
@Component
public class TimeoutScanScheduler {

    private final TimeoutScanner scanner;

    public TimeoutScanScheduler(TimeoutScanner scanner) {
        this.scanner = scanner;
    }

    @Scheduled(fixedDelayString = "${payment.reliability.timeout-scan-interval-ms:10000}")
    public void run() {
        // 调度线程不经 TraceIdFilter（spec 021 / AC3.1）：入口自建 traceId。
        TraceContext.runWithNewTrace(() -> scanner.scan(Instant.now()));
    }
}
