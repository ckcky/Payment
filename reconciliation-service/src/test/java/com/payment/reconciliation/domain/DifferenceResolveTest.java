package com.payment.reconciliation.domain;

import com.payment.common.core.error.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 差异处理依据（spec 006 T021 / ADR-0019）：说明 MUST 非空，处理人与时间 MUST 落值；
 * 重复处理按「幂等刷新」处理——同一差异被补充依据时不报错，直接覆盖为最新一次的处理记录。
 */
class DifferenceResolveTest {

    private Difference difference() {
        return Difference.of("ref-1", DifferenceType.AMOUNT_MISMATCH, 1000L, 900L, "SUCCEEDED", "SUCCEEDED");
    }

    @Test
    void blankNoteIsRejected() {
        Difference difference = difference();

        assertThatThrownBy(() -> difference.resolve("   ", "ops", "2026-08-31T00:00:00Z"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("resolution note must not be blank");
        assertThat(difference.isResolved()).isFalse();
    }

    @Test
    void nullNoteIsRejected() {
        Difference difference = difference();

        assertThatThrownBy(() -> difference.resolve(null, "ops", "2026-08-31T00:00:00Z"))
                .isInstanceOf(BizException.class);
        assertThat(difference.isResolved()).isFalse();
    }

    @Test
    void resolveRecordsNoteActorAndTime() {
        Difference difference = difference();

        difference.resolve("channel amount corrected by ops", "ops-1", "2026-08-31T10:00:00Z");

        assertThat(difference.isResolved()).isTrue();
        assertThat(difference.getResolutionNote()).isEqualTo("channel amount corrected by ops");
        assertThat(difference.getResolvedBy()).isEqualTo("ops-1");
        assertThat(difference.getResolvedAt()).isEqualTo("2026-08-31T10:00:00Z");
    }

    @Test
    void repeatedResolveRefreshesIdempotently() {
        Difference difference = difference();
        difference.resolve("first look", "ops-1", "2026-08-31T10:00:00Z");

        difference.resolve("confirmed with channel", "ops-2", "2026-08-31T11:00:00Z");

        assertThat(difference.isResolved()).isTrue();
        assertThat(difference.getResolutionNote()).isEqualTo("confirmed with channel");
        assertThat(difference.getResolvedBy()).isEqualTo("ops-2");
        assertThat(difference.getResolvedAt()).isEqualTo("2026-08-31T11:00:00Z");
    }

    @Test
    void batchUnresolvedCountFollowsResolve() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        batch.finish(java.util.List.of(), java.util.List.of(
                Difference.of("a", DifferenceType.PLATFORM_ONLY, 100L, null, "SUCCEEDED", null),
                Difference.of("b", DifferenceType.CHANNEL_ONLY, null, 200L, null, "SUCCEEDED")));

        assertThat(batch.unresolvedDifferenceCount()).isEqualTo(2);

        batch.getDifferences().get(0).resolve("ok", "ops", "2026-08-31T10:00:00Z");

        assertThat(batch.unresolvedDifferenceCount()).isEqualTo(1);
    }

    @Test
    void differenceAmountMinorTakesAbsoluteGapOnBothSidesAndSingleSideOtherwise() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        batch.finish(java.util.List.of(), java.util.List.of(
                // 双侧：|1000 - 900| = 100
                Difference.of("a", DifferenceType.AMOUNT_MISMATCH, 1000L, 900L, "SUCCEEDED", "SUCCEEDED"),
                // 仅平台侧：1000
                Difference.of("b", DifferenceType.PLATFORM_ONLY, 1000L, null, "SUCCEEDED", null),
                // 仅渠道侧：250
                Difference.of("c", DifferenceType.CHANNEL_ONLY, null, 250L, null, "SUCCEEDED")));

        assertThat(batch.differenceAmountMinor()).isEqualTo(100L + 1000L + 250L);
    }
}
