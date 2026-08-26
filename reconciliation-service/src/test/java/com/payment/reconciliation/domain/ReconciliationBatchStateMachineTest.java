package com.payment.reconciliation.domain;

import com.payment.common.core.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对账批次状态机测试：合法迁移与非法迁移。
 */
class ReconciliationBatchStateMachineTest {

    private Difference channelOnly() {
        return Difference.of("channel-extra-1", DifferenceType.CHANNEL_ONLY, null, 999L, null, "SUCCEEDED");
    }

    @Test
    void pendingToConsistentWhenNoDifferences() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.PENDING);

        batch.start();
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.RECONCILING);

        batch.finish(List.of(new Match("r", "PAYMENT", 1000L, "CNY")), List.of());
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.CONSISTENT);
    }

    @Test
    void pendingToHasDifferenceWhenDifferencesPresent() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        batch.finish(List.of(), List.of(channelOnly()));
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.HAS_DIFFERENCE);
    }

    @Test
    void hasDifferenceToProcessingToClosed() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        batch.finish(List.of(), List.of(channelOnly()));

        batch.beginProcessing();
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.PROCESSING);

        batch.close();
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.CLOSED);
    }

    @Test
    void consistentToClosed() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        batch.finish(List.of(), List.of());
        batch.close();
        assertThat(batch.getStatus()).isEqualTo(ReconciliationStatus.CLOSED);
    }

    @Test
    void startTwiceIsIllegal() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        assertThatThrownBy(batch::start).isInstanceOf(BizException.class);
    }

    @Test
    void finishBeforeReconcilingIsIllegal() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        assertThatThrownBy(() -> batch.finish(List.of(), List.of())).isInstanceOf(BizException.class);
    }

    @Test
    void beginProcessingWithoutDifferencesIsIllegal() {
        ReconciliationBatch batch = new ReconciliationBatch("2026-08", "mock-channel");
        batch.start();
        batch.finish(List.of(), List.of());
        assertThatThrownBy(batch::beginProcessing).isInstanceOf(BizException.class);
    }
}
