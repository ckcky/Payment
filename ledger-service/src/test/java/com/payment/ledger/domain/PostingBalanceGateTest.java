package com.payment.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountingEventType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 必测⑦（spec 031 §9 / ADR-0008 不变量）：借贷平衡是聚合根构造期门禁——
 * 不平衡的 Posting 直接被拒，不落任何分录；LedgerEntry 金额恒正、币种单一。
 */
class PostingBalanceGateTest {

    private static final String KEY = "PAYMENT_CAPTURE:PM-BG-1";

    @Test
    @DisplayName("借 > 贷 ⇒ LEDGER_UNBALANCED 拒绝")
    void debitHeavyRejected() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> posting(
                        entry(1, LedgerEntry.Direction.DEBIT, 100),
                        entry(2, LedgerEntry.Direction.CREDIT, 99)))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));
    }

    @Test
    @DisplayName("贷 > 借 ⇒ 同样拒绝（方向不对称不豁免）")
    void creditHeavyRejected() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> posting(
                        entry(1, LedgerEntry.Direction.DEBIT, 100),
                        entry(2, LedgerEntry.Direction.CREDIT, 101)))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));
    }

    @Test
    @DisplayName("平衡 ⇒ 放行；多行异账户同金额恒等亦可")
    void balancedAccepted() {
        assertThatNoException().isThrownBy(() -> posting(
                entry(1, LedgerEntry.Direction.DEBIT, 70),
                entry(2, LedgerEntry.Direction.DEBIT, 30),
                entry(3, LedgerEntry.Direction.CREDIT, 100)));
    }

    @Test
    @DisplayName("少于两行分录不构成交易（会计意义不成立）")
    void singleEntryRejected() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> posting(entry(1, LedgerEntry.Direction.DEBIT, 100)))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));
    }

    @Test
    @DisplayName("混币种分录 ⇒ 拒绝（同交易同币种不变量）")
    void mixedCurrencyRejected() {
        LedgerEntry usd = new LedgerEntry(null, 2, LedgerEntry.Direction.CREDIT, 100, "USD");
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> new Posting(AccountingEventType.PAYMENT_CAPTURE, KEY,
                        LedgerSourceType.PAYMENT, "PM-BG-1", "CNY",
                        List.of(entry(1, LedgerEntry.Direction.DEBIT, 100), usd)))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));
    }

    @Test
    @DisplayName("LedgerEntry 金额非正 ⇒ 直接拒绝（AMOUNT_INVARIANT_VIOLATION）")
    void nonPositiveEntryAmountRejected() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> new LedgerEntry(null, 1, LedgerEntry.Direction.DEBIT, 0, "CNY"))
                .matches(ex -> ErrorCodes.AMOUNT_INVARIANT_VIOLATION.equals(ex.getCode()));
    }

    @Test
    @DisplayName("rehydrate 同样过平衡门禁（持久化重建不豁免不变量）")
    void rehydrateStillValidates() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> Posting.rehydrate(9L, "LP-1", AccountingEventType.PAYMENT_CAPTURE,
                        KEY, LedgerSourceType.PAYMENT, "PM-BG-1", "CNY", "2026-09",
                        java.time.Instant.now(), Posting.Status.POSTED,
                        List.of(entry(1, LedgerEntry.Direction.DEBIT, 100),
                                entry(2, LedgerEntry.Direction.CREDIT, 60))))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));
    }

    @Test
    @DisplayName("平衡交易的 isBalanced 恒真（供关账前自检复用）")
    void balancedPostingReportsBalanced() {
        Posting p = posting(
                entry(1, LedgerEntry.Direction.DEBIT, 100),
                entry(2, LedgerEntry.Direction.CREDIT, 100));
        assertThat(p.isBalanced()).isTrue();
        assertThat(p.getEntries()).hasSize(2);
        assertThat(p.getPostingNo()).startsWith("LP");
    }

    private static LedgerEntry entry(long accountId, LedgerEntry.Direction direction, long amount) {
        return new LedgerEntry(null, accountId, direction, amount, "CNY");
    }

    private static Posting posting(LedgerEntry... entries) {
        return new Posting(AccountingEventType.PAYMENT_CAPTURE, KEY,
                LedgerSourceType.PAYMENT, "PM-BG-1", "CNY", List.of(entries));
    }
}
