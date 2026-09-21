package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.testsupport.ReconciliationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道资金事实入账（spec 032 §10.2 / G3，T17/T18）：netReceived=ΣPAYMENT−ΣREFUND(+ΣSETTLEMENT)、
 * channelFee=Σ FEE 行（唯一合法来源）；零净额/零费用跳过；CS-/CF- 确定性 sourceId（幂等重放由
 * ledger 派生键承接）；无可用账单 ⇒ 400 STATEMENT_UNAVAILABLE。
 */
class ChannelFundPostingServiceTest {

    private final InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
    private final RecordingGateway gateway = new RecordingGateway();
    private final ChannelFundPostingService service = new ChannelFundPostingService(
            imports, gateway, new NoopBusinessMetrics(), new StructuredAuditLogger());

    @Test
    void postsSettlementAndFeeEventsFromStatementRows() {
        ReconciliationTestSupport.seedImport(imports, "MOCK", "2026-08", List.of(
                line(1, "PAYMENT", "CH-1", 1000L, 0L),
                line(2, "REFUND", "CH-2", 300L, 0L),
                line(3, "FEE", "CH-FEE-1", 50L, 0L),
                line(4, "SETTLEMENT", "CH-STL-1", 100L, 0L)));

        ChannelFundPostingService.PostingOutcome outcome = service.postChannelFundFacts("MOCK", "2026-08");

        // netReceived = 1000 − 300 + 100 = 800；channelFee = 50（FEE 行唯一合法来源）
        assertThat(outcome.fact().netReceivedMinor()).isEqualTo(800L);
        assertThat(outcome.fact().channelFeeMinor()).isEqualTo(50L);
        assertThat(outcome.postedEvents()).containsExactly(
                "CHANNEL_SETTLEMENT:CS-MOCK-2026-08", "CHANNEL_FEE:CF-MOCK-2026-08");
        assertThat(gateway.requests).hasSize(2);
        AccountingEventRequest settlement = gateway.requests.get(0);
        assertThat(settlement.eventType()).isEqualTo(AccountingEventType.CHANNEL_SETTLEMENT.name());
        assertThat(settlement.sourceType()).isEqualTo("RECONCILIATION");
        assertThat(settlement.sourceId()).isEqualTo("CS-MOCK-2026-08");
        assertThat(settlement.netAmountMinor()).isEqualTo(800L);
        AccountingEventRequest fee = gateway.requests.get(1);
        assertThat(fee.eventType()).isEqualTo(AccountingEventType.CHANNEL_FEE.name());
        assertThat(fee.sourceId()).isEqualTo("CF-MOCK-2026-08");
        assertThat(fee.amountMinor()).isEqualTo(50L);
    }

    @Test
    void zeroNetAndZeroFeeProduceNoEvents() {
        ReconciliationTestSupport.seedImport(imports, "MOCK", "2026-08", List.of(
                line(1, "PAYMENT", "CH-1", 500L, 0L),
                line(2, "REFUND", "CH-2", 500L, 0L)));

        ChannelFundPostingService.PostingOutcome outcome = service.postChannelFundFacts("MOCK", "2026-08");

        assertThat(outcome.fact().netReceivedMinor()).isZero();
        assertThat(outcome.fact().channelFeeMinor()).isZero();
        assertThat(outcome.postedEvents()).isEmpty();
        assertThat(gateway.requests).isEmpty();
    }

    @Test
    void failedRowsDoNotEnterFundAggregation() {
        // 非成功行无资金移动：不进净额聚合（差异由匹配/核对层承载）
        ReconciliationTestSupport.seedImport(imports, "MOCK", "2026-08", List.of(
                line(1, "PAYMENT", "CH-1", 1000L, 0L),
                new StatementLine(null, 2, "MOCK", "TXN-2", "PAYMENT", "CH-3", "CHANNEL_TXN",
                        "M-1", 700L, 0L, "CNY", "FAILED", null, "raw")));

        ChannelFundPostingService.PostingOutcome outcome = service.postChannelFundFacts("MOCK", "2026-08");

        assertThat(outcome.fact().netReceivedMinor()).isEqualTo(1000L);
        assertThat(outcome.postedEvents()).containsExactly("CHANNEL_SETTLEMENT:CS-MOCK-2026-08");
    }

    @Test
    void replayIsDeterministicOnSourceIdForLedgerIdempotency() {
        ReconciliationTestSupport.seedImport(imports, "MOCK", "2026-08", List.of(
                line(1, "PAYMENT", "CH-1", 1000L, 0L)));

        ChannelFundPostingService.PostingOutcome first = service.postChannelFundFacts("MOCK", "2026-08");
        ChannelFundPostingService.PostingOutcome replay = service.postChannelFundFacts("MOCK", "2026-08");

        assertThat(replay.postedEvents()).isEqualTo(first.postedEvents());
        assertThat(gateway.requests).allMatch(r ->
                r.sourceId().startsWith("CS-MOCK-2026-08") || r.sourceId().startsWith("CF-MOCK-2026-08"));
    }

    @Test
    void noNormalizedImportIsRejectedWithStatementUnavailable() {
        assertThatThrownBy(() -> service.postChannelFundFacts("MOCK", "2026-07"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.STATEMENT_UNAVAILABLE));
        assertThat(gateway.requests).isEmpty();
    }

    private static StatementLine line(int lineNo, String type, String reference, long amountMinor, long feeMinor) {
        return new StatementLine(null, lineNo, "MOCK", "TXN-" + lineNo, type, reference, "CHANNEL_TXN",
                "M-1", amountMinor, feeMinor, "CNY", "SUCCEEDED", null, "raw-" + lineNo);
    }

    /** 记录式出站网关：只收集请求，不连 ledger。 */
    private static class RecordingGateway implements FundPostingGateway {
        final List<AccountingEventRequest> requests = new ArrayList<>();

        @Override
        public PostingResult postEvent(AccountingEventRequest request) {
            requests.add(request);
            return new PostingResult("LP-" + (requests.size()), String.valueOf(requests.size()));
        }
    }
}
