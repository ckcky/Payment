package com.payment.ledger.application;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.ledger.LedgerTestSupport;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.infra.InMemoryLedgerRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局平衡性与来源追溯测试（spec US4 / FR-007、FR-008）。
 */
class BalanceCheckerTest {

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerPostingService service = new LedgerPostingService(repository,
            new NoopBusinessMetrics(), new StructuredAuditLogger());
    private final BalanceChecker checker = new BalanceChecker(repository);

    @Test
    void emptyLedgerIsBalanced() {
        assertThat(checker.isBalanced()).isTrue();
        assertThat(checker.byCurrency()).isEmpty();
    }

    @Test
    void remainsBalancedAfterMixedPostings() {
        service.post("PAYMENT:1", LedgerSourceType.PAYMENT, "1", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "1", 10_000, 500));
        service.post("PAYMENT:2", LedgerSourceType.PAYMENT, "2", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "2", 4_000, 200));
        service.post("REFUND:1", LedgerSourceType.REFUND, "1", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "1", 10_000));

        assertThat(checker.isBalanced()).isTrue();
        assertThat(checker.byCurrency()).containsEntry("CNY", 0L);
    }

    @Test
    void entriesAreTraceableBySource() {
        service.post("PAYMENT:77", LedgerSourceType.PAYMENT, "77", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "77", 6_000, 180));

        assertThat(checker.entriesOfSource(LedgerSourceType.PAYMENT, "77")).hasSize(3);
        assertThat(repository.findBySource(LedgerSourceType.PAYMENT, "77")).hasSize(1);
        assertThat(checker.entriesOfSource(LedgerSourceType.REFUND, "77")).isEmpty();
    }
}
