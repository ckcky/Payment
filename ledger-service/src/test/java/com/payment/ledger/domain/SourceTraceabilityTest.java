package com.payment.ledger.domain;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.ledger.LedgerTestSupport;
import com.payment.ledger.application.BalanceChecker;
import com.payment.ledger.application.LedgerPostingService;
import com.payment.ledger.infra.InMemoryLedgerRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分录可追溯性测试（spec US4 / FR-008 / T018）：任意 LedgerEntry 都能按
 * {@code sourceType + sourceId} 追溯到业务来源，且不与其他来源串味。
 */
class SourceTraceabilityTest {

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerPostingService service = new LedgerPostingService(repository,
            new NoopBusinessMetrics(), new StructuredAuditLogger());
    private final BalanceChecker checker = new BalanceChecker(repository);

    /** 支付 8000（手续费 200）→ 退款 3000 → 结算 4800，三个来源各有独立分录。 */
    private void postThreeSources() {
        service.post("PAYMENT:p1", LedgerSourceType.PAYMENT, "p1", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p1", 8_000, 200));
        service.post("REFUND:r1", LedgerSourceType.REFUND, "r1", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r1", 3_000));
        service.post("SETTLEMENT:s1", LedgerSourceType.SETTLEMENT, "s1", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s1", 4_800));
    }

    @Test
    void everyEntryCarriesItsBusinessSource() {
        postThreeSources();

        for (LedgerEntry entry : repository.findAllEntries()) {
            assertThat(entry.getSourceType()).isNotNull();
            assertThat(entry.getSourceId()).isNotBlank();
            assertThat(entry.getPostingId()).isNotNull();
        }
    }

    @Test
    void entriesAreRetrievableBySourceTypeAndId() {
        postThreeSources();

        assertThat(checker.entriesOfSource(LedgerSourceType.PAYMENT, "p1")).hasSize(3);
        assertThat(checker.entriesOfSource(LedgerSourceType.REFUND, "r1")).hasSize(2);
        assertThat(checker.entriesOfSource(LedgerSourceType.SETTLEMENT, "s1")).hasSize(2);
    }

    @Test
    void sourceQueryDoesNotLeakAcrossSources() {
        postThreeSources();

        // 同类型不同 ID 不互相污染
        assertThat(checker.entriesOfSource(LedgerSourceType.PAYMENT, "r1")).isEmpty();
        assertThat(checker.entriesOfSource(LedgerSourceType.PAYMENT, "s1")).isEmpty();
        // 不同类型同 ID 不互相污染
        assertThat(checker.entriesOfSource(LedgerSourceType.REFUND, "p1")).isEmpty();
        assertThat(checker.entriesOfSource(LedgerSourceType.SETTLEMENT, "p1")).isEmpty();
    }

    @Test
    void refundAndSettlementEntriesUseTheirOwnEntryTypes() {
        postThreeSources();

        assertThat(checker.entriesOfSource(LedgerSourceType.REFUND, "r1"))
                .allMatch(e -> e.getEntryType() == LedgerEntry.Type.REFUND);
        assertThat(checker.entriesOfSource(LedgerSourceType.SETTLEMENT, "s1"))
                .allMatch(e -> e.getEntryType() == LedgerEntry.Type.SETTLEMENT);
    }
}
