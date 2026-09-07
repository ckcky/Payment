package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 审计日切调度（spec 017 / FR-010）：日切后自动触发 T-1 的账证 + 账账核对
 * （沿用 OrderTimeoutScheduler / ChannelQueryScheduler 先例，@Scheduled + 手动端点并存）。
 * 核对窗口避开实时交易（NFR-002）；失败仅告警不重试入批（NFR-008，可安全重跑）。
 */
@Component
public class AuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditScheduler.class);

    private final AuditApplicationService applicationService;
    private final boolean enabled;

    public AuditScheduler(AuditApplicationService applicationService,
                          @Value("${audit.schedule.enabled:true}") boolean enabled) {
        this.applicationService = applicationService;
        this.enabled = enabled;
    }

    /** 每天 00:30（东八区）触发 T-1 账证核对；ALL 一次覆盖四核对。 */
    @Scheduled(cron = "${audit.schedule.cron:0 30 0 * * *}", zone = "Asia/Shanghai")
    public void runDailyAudit() {
        // 调度线程不经 TraceIdFilter（spec 021 / AC3.1）：入口自建 traceId。
        com.payment.common.core.trace.TraceContext.runWithNewTrace(this::doRunDailyAudit);
    }

    private void doRunDailyAudit() {
        if (!enabled) {
            return;
        }
        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        try {
            var batch = applicationService.runBatch(yesterday, AuditScope.ALL.name(), "scheduler:daily");
            log.info("daily audit finished: batchNo={} status={} differences={}",
                    batch.getBatchNo(), batch.getStatus(), batch.getDifferences().size());
        } catch (RuntimeException ex) {
            // 失效安全：批次不落半成品，次日/人工可安全重跑
            log.error("daily audit failed (safe to rerun): period={} reason={}", yesterday, ex.getMessage());
        }
    }
}
