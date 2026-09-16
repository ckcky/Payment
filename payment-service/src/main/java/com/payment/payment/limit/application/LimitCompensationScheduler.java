package com.payment.payment.limit.application;

import com.payment.common.core.trace.TraceContext;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 额度结算补偿调度器（spec 027 / FR-016）。
 *
 * <p>复用既有 {@code TimeoutScanScheduler} 的模式（ADR-0004 / spec 021 AC3.1）：
 * 调度线程不经 {@code TraceIdFilter}，故入口自建 traceId，否则补偿产生的日志无法与
 * 请求链路关联（排障时表现为「有日志但查不到是哪次触发」）。</p>
 *
 * <p>间隔由 {@code payment.limit.compensation-interval-ms} 配置（默认 30000ms）。
 * 需 {@code @EnableScheduling}（已在 {@code PaymentApplication} 启用）。</p>
 */
@Component
public class LimitCompensationScheduler {

    private final LimitCompensationScanner scanner;

    public LimitCompensationScheduler(LimitCompensationScanner scanner) {
        this.scanner = scanner;
    }

    @Scheduled(fixedDelayString = "${payment.limit.compensation-interval-ms:30000}")
    public void run() {
        TraceContext.runWithNewTrace(() -> scanner.scan(Instant.now()));
    }
}
