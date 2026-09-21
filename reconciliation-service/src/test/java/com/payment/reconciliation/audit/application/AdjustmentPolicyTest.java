package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.audit.domain.AuditAdjustmentKind;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditScope;
import com.payment.common.core.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 挂账 / 调账硬规则单测（spec 017 / T058）：FR-016 七条 + 金额边界（0 / 负数 / 超额 / 双人复核）。
 */
class AdjustmentPolicyTest {

    // ---- validate（FR-016 ⑤⑥ + WRITE_OFF 门禁）----

    @Test
    void zeroOrNegativeAmountRejected() {
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, 0L,
                "op", null, "reason", false, false))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, -1L,
                "op", null, "reason", false, false))
                .isInstanceOf(BizException.class);
    }

    @Test
    void operatorAndReasonRequired() {
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, 100L,
                null, null, "reason", false, false))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, 100L,
                "op", null, " ", false, false))
                .isInstanceOf(BizException.class);
    }

    @Test
    void writeOffDisabledByDefault() {
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.WRITE_OFF, 100L,
                "op", "rev", "reason", false, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("WRITE_OFF disabled");
    }

    @Test
    void doubleCheckSoftVsHard() {
        // 软约束（默认）：不抛
        AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, 20000L, "op", null, "reason", false, false);
        // 硬约束（配置开启）：缺复核人 → 拒绝
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, 20000L,
                "op", null, "reason", false, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("double-check");
        // 同人复核 → 拒绝
        assertThatThrownBy(() -> AdjustmentPolicy.validate(AuditAdjustmentKind.SUPPLEMENT, 20000L,
                "op", "op", "reason", false, true))
                .isInstanceOf(BizException.class);
    }

    @Test
    void needsReviewThreshold() {
        assertThat(AdjustmentPolicy.needsReview(AuditAdjustmentKind.SUPPLEMENT, 10000L, "op", "rev")).isFalse();
        assertThat(AdjustmentPolicy.needsReview(AuditAdjustmentKind.SUPPLEMENT, 10001L, "op", "rev")).isTrue();
        assertThat(AdjustmentPolicy.needsReview(AuditAdjustmentKind.SUPPLEMENT, 100L, "op", null)).isTrue();
        assertThat(AdjustmentPolicy.needsReview(AuditAdjustmentKind.WRITE_OFF, 100L, "op", "rev")).isTrue();
    }

    // ---- 差异金额约束（FR-016 ④：累计 ≤ 差异金额，SC-012）----

    @Test
    void exceedDifferenceAmountRejected() {
        AuditDifference difference = AuditDifference.of(AuditDifferenceKind.MISSING_POSTING, "PAYMENT",
                "PM-AUD-0003", null, 8000L, 0L, "CNY", "漏记账");
        difference.suspend(8000L);
        assertThatThrownBy(() -> difference.applyAdjustment(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ADJUST_AMOUNT_EXCEEDED");
    }

    @Test
    void suspendThenTransferAccumulation() {
        AuditDifference difference = AuditDifference.of(AuditDifferenceKind.MISSING_POSTING, "PAYMENT",
                "PM-AUD-0003", null, 8000L, 0L, "CNY", "漏记账");
        difference.suspend(5000L);
        difference.applyAdjustment(3000L);
        assertThat(difference.getStatus().name()).isEqualTo("ADJUSTED");
        assertThatThrownBy(() -> difference.applyAdjustment(1L))
                .isInstanceOf(BizException.class);
    }

    // ---- ADJUSTMENT 事件计划编排（SC-008 / SC-011 / SC-013，spec 031 §7.6/§9：方向由账本推导）----

    @Test
    void suspendUnderRecordedPlansBankCashToSuspense() {
        // 账少记：借资金 / 贷 SUSPENSE ⇒ to=BANK_CASH、from=SUSPENSE（账本展开 Dr to / Cr from）
        AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(AuditAdjustmentKind.SUSPEND,
                true, 8000L, null, null);
        assertThat(plan.adjustmentKind()).isEqualTo("SUSPEND");
        assertThat(plan.amountMinor()).isEqualTo(8000L);
        assertThat(plan.fromAccountCode()).isEqualTo("SUSPENSE");
        assertThat(plan.toAccountCode()).isEqualTo("BANK_CASH");
        assertThat(plan.reversesEventType()).isNull();
    }

    @Test
    void suspendOverRecordedReversesTransferDirection() {
        AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(AuditAdjustmentKind.SUSPEND,
                false, 5000L, null, null);
        assertThat(plan.fromAccountCode()).isEqualTo("BANK_CASH");
        assertThat(plan.toAccountCode()).isEqualTo("SUSPENSE");
    }

    @Test
    void reverseRequiresOriginalPosting() {
        assertThatThrownBy(() -> AdjustmentPolicy.buildPlan(AuditAdjustmentKind.REVERSE,
                false, 5000L, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("requires an original posting");
    }

    @Test
    void reverseCarriesOnlyRedFlushRefNoLocalEntries() {
        // 红冲不再本地拼分录：只声明冲销引用，取反由账本 AdjustmentRule 读原交易完成（append-only）
        AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(AuditAdjustmentKind.REVERSE,
                false, 5000L, null, new AdjustmentPolicy.OriginalRef("PAYMENT", "PM-AUD-0011"));
        assertThat(plan.adjustmentKind()).isEqualTo("REVERSE");
        assertThat(plan.fromAccountCode()).isNull();
        assertThat(plan.toAccountCode()).isNull();
        assertThat(plan.reversesEventType()).isEqualTo("PAYMENT_CAPTURE");
        assertThat(plan.reversesSourceId()).isEqualTo("PM-AUD-0011");
    }

    @Test
    void correctCombinesRedFlushRefAndTransferLeg() {
        AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(AuditAdjustmentKind.CORRECT,
                false, 6000L, "MERCHANT_PAYABLE",
                new AdjustmentPolicy.OriginalRef("REFUND", "RF-AUD-0007"));
        assertThat(plan.reversesEventType()).isEqualTo("REFUND");
        assertThat(plan.reversesSourceId()).isEqualTo("RF-AUD-0007");
        assertThat(plan.fromAccountCode()).isEqualTo("MERCHANT_PAYABLE");
        assertThat(plan.toAccountCode()).isEqualTo("BANK_CASH");
    }

    @Test
    void transferMovesOutOfSuspenseOppositeToSuspend() {
        // 账少记挂账后转出：借 SUSPENSE / 贷目标 ⇒ from=目标、to=SUSPENSE
        AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(AuditAdjustmentKind.TRANSFER,
                true, 8000L, null, null);
        assertThat(plan.fromAccountCode()).isEqualTo("MERCHANT_PAYABLE");
        assertThat(plan.toAccountCode()).isEqualTo("SUSPENSE");
    }

    @Test
    void supplementMapsLegacyFeeCodeAndRejectsUnknownCode() {
        AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(AuditAdjustmentKind.SUPPLEMENT,
                false, 300L, "PLATFORM_FEE_REVENUE", null);
        // 旧码 PLATFORM_FEE_REVENUE 就地更名 FEE_REVENUE（ADR-0078）
        assertThat(plan.fromAccountCode()).isEqualTo("FEE_REVENUE");
        assertThatThrownBy(() -> AdjustmentPolicy.buildPlan(AuditAdjustmentKind.SUPPLEMENT,
                false, 300L, "NOT_AN_ACCOUNT", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("unknown account code");
    }

    @Test
    void reverseRefusesSourceTypesWithoutReversibleEvent() {
        assertThatThrownBy(() -> AdjustmentPolicy.buildPlan(AuditAdjustmentKind.REVERSE,
                false, 100L, null, new AdjustmentPolicy.OriginalRef("FOO", "X-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("no reversible event");
    }
}
