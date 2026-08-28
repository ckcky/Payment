package com.payment.payment.application.reliability;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 重试调度器（spec US3 / ADR-0005）：按固定间隔驱动 {@link PaymentRetryService#retryDue(Instant)}。
 *
 * <p>间隔由 {@code payment.reliability.retry-scan-interval-ms} 配置（默认 5s）。退避由
 * {@code nextRetryAt} 控制，调度间隔只决定扫描频率（ADR-0013）。</p>
 */
@Component
public class PaymentRetryScheduler {

    private final PaymentRetryService retryService;

    public PaymentRetryScheduler(PaymentRetryService retryService) {
        this.retryService = retryService;
    }

    @Scheduled(fixedDelayString = "${payment.reliability.retry-scan-interval-ms:5000}")
    public void run() {
        retryService.retryDue(Instant.now());
    }
}
