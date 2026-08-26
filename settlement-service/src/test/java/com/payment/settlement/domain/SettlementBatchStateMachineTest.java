package com.payment.settlement.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算批次状态机测试：验证合法转换、未知收敛、终态保护与非法跳转拒绝。
 */
class SettlementBatchStateMachineTest {

    private SettlementBatch newBatch() {
        return new SettlementBatch("m-1", "2026-08", "CNY", "idem-1");
    }

    @Test
    void pendingToCalculatingToReadyToExecutingToUnknown() {
        SettlementBatch batch = newBatch();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.PENDING);

        batch.calculate(5000L, 1000L, 0L, "CNY");
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.CALCULATING);
        assertThat(batch.getIncomeMinor()).isEqualTo(5000L);
        assertThat(batch.getRefundMinor()).isEqualTo(1000L);
        assertThat(batch.getNetMinor()).isEqualTo(4000L);

        batch.markReady();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.READY);

        batch.execute();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.EXECUTING);

        assertThat(batch.markUnknown("unknown payout")).isTrue();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.UNKNOWN);
    }

    @Test
    void unknownConvergesToSucceeded() {
        SettlementBatch batch = readyExecutingUnknownBatch();

        assertThat(batch.succeed()).isTrue();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.SUCCEEDED);
    }

    @Test
    void unknownConvergesToFailed() {
        SettlementBatch batch = readyExecutingUnknownBatch();

        assertThat(batch.fail("resolved failed")).isTrue();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.FAILED);
    }

    @Test
    void terminalAbsorbsLateConflictingResult() {
        SettlementBatch batch = readyExecutingUnknownBatch();
        assertThat(batch.succeed()).isTrue();

        assertThat(batch.fail("late decline")).isFalse();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.SUCCEEDED);
    }

    @Test
    void executeBeforeReadyIsRejected() {
        SettlementBatch batch = newBatch();

        assertThatThrownBy(batch::execute)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_TRANSITION_VIOLATION));
    }

    @Test
    void negativeAmountIsRejected() {
        SettlementBatch batch = newBatch();

        assertThatThrownBy(() -> batch.calculate(-1L, 0L, 0L, "CNY"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.AMOUNT_INVARIANT_VIOLATION));
    }

    @Test
    void closeFromSucceededTransitionsToClosed() {
        SettlementBatch batch = readyExecutingUnknownBatch();
        batch.succeed();

        batch.close();
        assertThat(batch.getStatus()).isEqualTo(SettlementStatus.CLOSED);
    }

    private SettlementBatch readyExecutingUnknownBatch() {
        SettlementBatch batch = newBatch();
        batch.calculate(5000L, 1000L, 0L, "CNY");
        batch.markReady();
        batch.execute();
        batch.markUnknown("unknown payout");
        return batch;
    }
}
