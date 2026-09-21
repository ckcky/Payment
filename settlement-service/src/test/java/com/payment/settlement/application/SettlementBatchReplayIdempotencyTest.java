package com.payment.settlement.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementStatus;
import com.payment.settlement.infra.InMemorySettlementAdjustmentRepository;
import com.payment.settlement.infra.InMemorySettlementRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TT-11（spec 034 §13）：结算批次「同 batchNo 重放」幂等——收敛回调重复投递时，
 * 状态机终态吸收（SUCCEEDED → SUCCEEDED 返回 false），记账事件 MUST NOT 再次发出。
 * 幂等不靠账本目的地兜底，出站事件本身只发一次（与 RefundResultProcessor 终态吸收同构）。
 */
class SettlementBatchReplayIdempotencyTest {

    private final InMemorySettlementRepository repository = new InMemorySettlementRepository();
    private final InMemorySettlementAdjustmentRepository adjustmentRepository =
            new InMemorySettlementAdjustmentRepository();
    private final FakeMerchantClient merchantClient = new FakeMerchantClient();
    private final FakeReconciliationClient reconciliationClient = new FakeReconciliationClient();
    private final FakeLedgerPostingGateway ledgerGateway = new FakeLedgerPostingGateway();
    private final RecordingMetrics metrics = new RecordingMetrics();

    private SettlementApplicationService service() {
        return new SettlementApplicationService(repository, merchantClient, reconciliationClient,
                adjustmentRepository, ledgerGateway, AuditGateClient.disabled(),
                metrics, new StructuredAuditLogger());
    }

    @Test
    void replayedSucceededResolutionDoesNotPostLedgerTwice() {
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-tt11");

        svc.resolveBatch(created.getId(), "SUCCEEDED");
        assertThat(ledgerGateway.postedBatchNos).containsExactly(created.getBatchNo());
        assertThat(ledgerGateway.postedNet).containsExactly(4000L);

        // 重放：同批次再次收到 SUCCEEDED 收敛（重复投递）
        SettlementBatch replayed = svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(replayed.getStatus()).isEqualTo(SettlementStatus.SUCCEEDED);
        assertThat(ledgerGateway.postedBatchNos).as("TT-11：重放 MUST NOT 再次发记账事件")
                .containsExactly(created.getBatchNo());
        assertThat(ledgerGateway.postedNet).containsExactly(4000L);
        assertThat(metrics.counts.get("settlement.ledger_replay_absorbed")).isEqualTo(1.0);
        assertThat(metrics.counts.containsKey("settlement.ledger_skip_zero_net")).isFalse();
    }

    @Test
    void replayAfterCloseIsStillAbsorbedWithoutSecondPosting() {
        SettlementApplicationService svc = service();
        SettlementBatch created = svc.createBatch("1", "2026-08", "idem-tt11-close");
        svc.resolveBatch(created.getId(), "SUCCEEDED");
        svc.closeBatch(created.getId(), "ops-tt11");

        // 关闭后再收到同批次 SUCCEEDED 收敛重放（乱序到达）
        SettlementBatch replayed = svc.resolveBatch(created.getId(), "SUCCEEDED");

        assertThat(replayed.getStatus()).isEqualTo(SettlementStatus.CLOSED);
        assertThat(ledgerGateway.postedBatchNos).as("CLOSED 终态吸收，不重复记账")
                .containsExactly(created.getBatchNo());
        assertThat(metrics.counts.get("settlement.ledger_replay_absorbed")).isEqualTo(1.0);
    }

    private static final class FakeMerchantClient implements MerchantClient {

        private MerchantView view = new MerchantView(1L, "ACTIVE", true);

        @Override
        public MerchantView getMerchant(Long merchantId) {
            return view;
        }
    }

    private static final class FakeReconciliationClient implements ReconciliationClient {

        private List<SettlementFact> facts = List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY"));

        @Override
        public ReconciliationSummary getSettlementSummary(String period) {
            return new ReconciliationSummary(period, facts, 0);
        }
    }

    private static final class FakeLedgerPostingGateway implements LedgerPostingGateway {

        private final List<String> postedBatchNos = new ArrayList<>();
        private final List<Long> postedNet = new ArrayList<>();

        @Override
        public void postMerchantSettlement(LedgerPostingGateway.MerchantSettlementFacts facts) {
            postedBatchNos.add(facts.batchNo());
            postedNet.add(facts.netMinor());
        }
    }

    private static final class RecordingMetrics implements BusinessMetrics {

        private final Map<String, Double> counts = new HashMap<>();

        @Override
        public void counter(String name, double value, String... tags) {
            counts.merge(name, value, Double::sum);
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
            // not asserted here
        }
    }
}
