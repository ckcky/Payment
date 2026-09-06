package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.settlement.domain.SettlementAdjustmentRepository;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementStatus;
import com.payment.settlement.infra.InMemorySettlementAdjustmentRepository;
import com.payment.settlement.infra.InMemorySettlementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计结算门禁（spec 017 / plan §6.1 分级门禁）：
 * BLOCK 拒绝建批、ALLOW 放行、门禁不可达 fail-closed、开关关闭恒放行。
 */
class AuditSettlementGateTest {

    private final InMemorySettlementRepository repository = new InMemorySettlementRepository();
    private final SettlementAdjustmentRepository adjustmentRepository = new InMemorySettlementAdjustmentRepository();

    private SettlementApplicationService service(AuditGateClient gateClient) {
        MerchantClient merchantClient = id -> new MerchantView(id, "ACTIVE", true);
        ReconciliationClient reconciliationClient = period -> new ReconciliationSummary(period,
                List.of(new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY")), 0);
        LedgerPostingGateway ledgerGateway = (idempotencyKey, batchId, netMinor, currencyCode) -> { };
        return new SettlementApplicationService(repository, merchantClient, reconciliationClient,
                adjustmentRepository, ledgerGateway, gateClient,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    @Test
    void blockedGateRejectsBatchCreation() {
        AuditGateClient blocking = period -> new AuditGateDecision("BLOCK", true,
                List.of(new AuditGateDecision.BlockingDifference(
                        "MISSING_POSTING", "PAYMENT", "PM-AUD-0003", "BLOCKER", 8000L, "CNY")));

        assertThatThrownBy(() -> service(blocking).createBatch("1", "2026-08", "idem-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("audit settlement gate blocked")
                .hasMessageContaining("PM-AUD-0003");
    }

    @Test
    void allowGatePasses() {
        AuditGateClient allow = period -> new AuditGateDecision("ALLOW", true, List.of());

        SettlementBatch batch = service(allow).createBatch("1", "2026-08", "idem-1");
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.UNKNOWN);
    }

    @Test
    void unavailableGateFailsClosed() {
        AuditGateClient unavailable = period -> {
            throw new IllegalStateException("connection refused");
        };

        // fail-closed（宁拦勿放）：门禁不可达时建批被阻断（Feign 实现层会把异常归一化为
        // STATE_TRANSITION_VIOLATION，本测试用 fake 直接验证「抛异常 ⇒ 不落批次」）
        assertThatThrownBy(() -> service(unavailable).createBatch("1", "2026-08", "idem-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
        assertThat(repository.findByIdempotencyKey("idem-1")).isEmpty();
    }

    @Test
    void disabledGatePasses() {
        SettlementBatch batch = service(AuditGateClient.disabled()).createBatch("1", "2026-08", "idem-1");
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.UNKNOWN);
    }
}
