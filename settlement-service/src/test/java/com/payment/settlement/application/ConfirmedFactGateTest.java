package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 已确认事实闸门（ADR-0023 + 032/G4、AC-3）：周期一致、事实类型白名单、金额非负、
 * 币种一致、商户归属校验（缺 merchantId 拒绝 / 他商户滤出）与拒绝指标留痕。
 */
class ConfirmedFactGateTest {

    @Test
    void passesAndReturnsOnlyRequestMerchantFacts() {
        ReconciliationSummary summary = new ReconciliationSummary("2026-08", List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY", "1"),
                new SettlementFact("ref-2", "REFUND", 1000L, "CNY", "2")),
                List.of(), 0);

        List<SettlementFact> settleable = ConfirmedFactGate.gate(summary, "CNY", "2026-08", "1",
                new NoopBusinessMetrics());

        // 归属他商户的事实滤出结算口径，只返回本商户事实
        assertThat(settleable).hasSize(1);
        assertThat(settleable.get(0).reference()).isEqualTo("ref-1");
    }

    @Test
    void periodMismatchIsRejected() {
        ReconciliationSummary summary = new ReconciliationSummary("2026-07", List.of(), 0);

        assertThatThrownBy(() -> ConfirmedFactGate.gate(summary, "CNY", "2026-08", "1",
                new NoopBusinessMetrics()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
    }

    @Test
    void merchantMissingFactIsRejectedWithReasonMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);
        ReconciliationSummary summary = new ReconciliationSummary("2026-08", List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY", "1"),
                // 032/AC-3：归属未知（缺 merchantId）⇒ 拒绝，未知归属不得结算
                new SettlementFact("ref-3", "PAYMENT", 300L, "CNY")),
                List.of(), 0);

        assertThatThrownBy(() -> ConfirmedFactGate.gate(summary, "CNY", "2026-08", "1", metrics))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT))
                .hasMessageContaining("merchant_missing");
        assertThat(registry.get("settlement.gate_rejected").tag("reason", "merchant_missing")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void unknownFactTypeAndNegativeAmountAndCurrencyMismatchAreRejected() {
        NoopBusinessMetrics metrics = new NoopBusinessMetrics();

        ReconciliationSummary unknownType = new ReconciliationSummary("2026-08",
                List.of(new SettlementFact("ref-1", "ADJUSTMENT", 5000L, "CNY", "1")), List.of(), 0);
        ReconciliationSummary negative = new ReconciliationSummary("2026-08",
                List.of(new SettlementFact("ref-1", "REFUND", -1L, "CNY", "1")), List.of(), 0);
        ReconciliationSummary currency = new ReconciliationSummary("2026-08",
                List.of(new SettlementFact("ref-1", "PAYMENT", 5000L, "USD", "1")), List.of(), 0);

        for (ReconciliationSummary bad : List.of(unknownType, negative, currency)) {
            assertThatThrownBy(() -> ConfirmedFactGate.gate(bad, "CNY", "2026-08", "1", metrics))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.INVALID_ARGUMENT));
        }
    }

    @Test
    void otherMerchantOnlyIsNotRejectedButYieldsEmptyScope() {
        // 全部事实归属他商户 ⇒ 不拒绝（留痕滤除），本商户可结算事实为空
        ReconciliationSummary summary = new ReconciliationSummary("2026-08", List.of(
                new SettlementFact("ref-1", "PAYMENT", 5000L, "CNY", "2")),
                List.of(), 0);

        List<SettlementFact> settleable = ConfirmedFactGate.gate(summary, "CNY", "2026-08", "1",
                new NoopBusinessMetrics());

        assertThat(settleable).isEmpty();
    }
}
