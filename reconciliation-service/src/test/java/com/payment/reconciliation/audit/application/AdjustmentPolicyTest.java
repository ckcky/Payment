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

    // ---- 分录编排（SC-008 / SC-011 / SC-013）----

    @Test
    void suspendEntriesBalancedUnderRecorded() {
        AdjustmentPolicy.PostingPlan plan = AdjustmentPolicy.buildPlan("AD-1",
                AuditAdjustmentKind.SUSPEND, true, 8000L, null, null);
        assertThat(plan.idempotencyKey()).isEqualTo("adjust:AD-1");
        long debits = plan.entries().stream().filter(e -> "DEBIT".equals(e.direction())).mapToLong(AdjustmentPolicy.PostingEntry::amountMinor).sum();
        long credits = plan.entries().stream().filter(e -> "CREDIT".equals(e.direction())).mapToLong(AdjustmentPolicy.PostingEntry::amountMinor).sum();
        assertThat(debits).isEqualTo(credits).isEqualTo(8000L);
        // 账少记：借 CUSTOMER_CASH(1) / 贷 SUSPENSE(5)
        assertThat(plan.entries()).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(1L);
            assertThat(e.direction()).isEqualTo("DEBIT");
        });
        assertThat(plan.entries()).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(5L);
            assertThat(e.direction()).isEqualTo("CREDIT");
        });
    }

    @Test
    void suspendEntriesReversedWhenOverRecorded() {
        AdjustmentPolicy.PostingPlan plan = AdjustmentPolicy.buildPlan("AD-2",
                AuditAdjustmentKind.SUSPEND, false, 5000L, null, null);
        // 账多记：借 SUSPENSE(5) / 贷 CUSTOMER_CASH(1)
        assertThat(plan.entries().get(0).accountId()).isEqualTo(5L);
        assertThat(plan.entries().get(0).direction()).isEqualTo("DEBIT");
        assertThat(plan.entries().get(1).accountId()).isEqualTo(1L);
        assertThat(plan.entries().get(1).direction()).isEqualTo("CREDIT");
    }

    @Test
    void reverseRequiresOriginalPosting() {
        assertThatThrownBy(() -> AdjustmentPolicy.buildPlan("AD-3", AuditAdjustmentKind.REVERSE,
                false, 5000L, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("requires an original posting");
    }

    @Test
    void reversePreservesAppendOnlyAndReversesDirections() {
        List<AdjustmentPolicy.PostingEntry> original = List.of(
                new AdjustmentPolicy.PostingEntry(1L, "DEBIT", 5000L),
                new AdjustmentPolicy.PostingEntry(2L, "CREDIT", 5000L));
        AdjustmentPolicy.PostingPlan plan = AdjustmentPolicy.buildPlan("AD-3",
                AuditAdjustmentKind.REVERSE, false, 5000L, null, original);
        assertThat(plan.idempotencyKey()).isEqualTo("adjust:AD-3");
        assertThat(plan.entries().get(0).direction()).isEqualTo("CREDIT");
        assertThat(plan.entries().get(1).direction()).isEqualTo("DEBIT");
    }

    @Test
    void transferMovesOutOfSuspense() {
        AdjustmentPolicy.PostingPlan plan = AdjustmentPolicy.buildPlan("AD-4",
                AuditAdjustmentKind.TRANSFER, true, 8000L, "MERCHANT_PAYABLE", null);
        // 账少记挂账后转出：借 SUSPENSE(5) / 贷 MERCHANT_PAYABLE(2)
        assertThat(plan.entries().get(0).accountId()).isEqualTo(5L);
        assertThat(plan.entries().get(1).accountId()).isEqualTo(2L);
    }
}
