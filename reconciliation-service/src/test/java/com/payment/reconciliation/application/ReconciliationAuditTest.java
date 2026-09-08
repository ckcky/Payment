package com.payment.reconciliation.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.infra.InMemoryReconciliationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资金审计留痕（spec 006 T038 / Constitution §6）：差异处理与批次关闭各写一条
 * {@code FINANCIAL_AUDIT}，含 traceId / 前后状态；审计里不得出现渠道原始报文等敏感明细。
 */
class ReconciliationAuditTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        auditLogger = (Logger) LoggerFactory.getLogger("FINANCIAL_AUDIT");
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(appender);
        appender.stop();
    }

    private ReconciliationApplicationService service() {
        return new ReconciliationApplicationService(
                new InMemoryReconciliationRepository(),
                () -> List.of(new PlatformFact("pay-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                List::of,
                period -> new ChannelStatementLoadResult(
                        List.of(new ChannelStatement("pay-1", 1000L, "CNY", "SUCCEEDED"),
                                new ChannelStatement("channel-extra-1", 900L, "CNY", "SUCCEEDED")),
                        ChannelStatementSource.fixture("inline", 2, false)),
                new MicrometerBusinessMetrics(new SimpleMeterRegistry()),
                new StructuredAuditLogger());
    }

    @Test
    void resolveAndCloseEachWriteOneFinancialAudit() {
        ReconciliationApplicationService service = service();
        var batch = service.runReconciliation("2026-08-31");

        service.resolveDifference(batch.getId(), "channel-extra-1", "checked with channel", "ops", null);
        service.closeBatch(batch.getId(), "ops-closer");

        List<String> events = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(events).anyMatch(e -> e.contains("reconciliation.difference_resolved"));
        assertThat(events).anyMatch(e -> e.contains("reconciliation.batch_closed"));
        assertThat(events).allMatch(e -> e.contains("traceId"));
    }

    @Test
    void auditCarriesStatusTransitionAndTraceId() {
        ReconciliationApplicationService service = service();
        var batch = service.runReconciliation("2026-08-31");

        service.resolveDifference(batch.getId(), "channel-extra-1", "checked", "ops", null);

        String event = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(e -> e.contains("reconciliation.difference_resolved"))
                .findFirst().orElseThrow();
        assertThat(event).contains("\"fromStatus\":\"HAS_DIFFERENCE\"");
        assertThat(event).contains("\"toStatus\":\"PROCESSING\"");
        assertThat(event).contains("traceId");
    }

    @Test
    void auditDoesNotLeakSensitiveChannelPayload() {
        ReconciliationApplicationService service = service();
        var batch = service.runReconciliation("2026-08-31");
        for (Difference difference : List.copyOf(batch.getDifferences())) {
            service.resolveDifference(batch.getId(), difference.getReference(),
                    "note with card-like token 4111111111111111", "ops", null);
        }
        service.closeBatch(batch.getId(), "ops");

        List<String> events = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        // 审计只记结构化字段（action/traceId/状态/实体），不回显处理说明正文
        assertThat(events).noneMatch(e -> e.contains("4111111111111111"));
    }
}
