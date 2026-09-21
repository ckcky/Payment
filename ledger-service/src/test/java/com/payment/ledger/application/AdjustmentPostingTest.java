package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.adjustment;
import static com.payment.ledger.LedgerTestSupport.entrySummary;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADJUSTMENT 规则（spec 031 §7.6 / 017 五类调账事件化）：转账语义恒「Dr to / Cr from」；
 * 红冲由账本读原交易逐行取反（append-only，必测⑨）；WRITE_OFF 维持否决。
 */
class AdjustmentPostingTest {

    @Test
    @DisplayName("转账语义：SUSPEND（from BANK_CASH → to SUSPENSE）展开 Dr 挂账 / Cr 银行现金")
    void suspendTransfersBankCashToSuspense() {
        Wiring w = wiring();
        Posting posting = w.engine().post(
                adjustment("ADJ-SUS-1", "SUSPEND", 3000, AccountCode.BANK_CASH.name(),
                        AccountCode.SUSPENSE.name(), null, null, null));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT SUSPENSE:PLATFORM 3000",
                "CREDIT BANK_CASH:PLATFORM 3000");
        assertThat(w.signedBalance(AccountCode.SUSPENSE, "PLATFORM")).isEqualTo(3000);
    }

    @Test
    @DisplayName("转账到商户维度科目：ownerKey 取事件 merchantId（分户落账）")
    void transferToMerchantPayableUsesMerchantOwner() {
        Wiring w = wiring();
        Posting posting = w.engine().post(
                adjustment("ADJ-TR-1", "TRANSFER", 800, AccountCode.SUSPENSE.name(),
                        AccountCode.MERCHANT_PAYABLE.name(), null, null, "M001"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT MERCHANT_PAYABLE:M001 800",
                "CREDIT SUSPENSE:PLATFORM 800");
        // 转账「挂账 → 应付」即借记应付：贷余科目被借记，取号 -800
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(-800);
        assertThat(w.signedBalance(AccountCode.SUSPENSE, "PLATFORM")).isEqualTo(-800);
    }

    @Test
    @DisplayName("merchantId 缺省落 LEGACY 哨兵实例（017 应付总口径），不新开户")
    void merchantWithoutOwnerHitsLegacySentinel() {
        Wiring w = wiring();
        Posting posting = w.engine().post(
                adjustment("ADJ-TR-2", "SUPPLEMENT", 500, AccountCode.BANK_CASH.name(),
                        AccountCode.MERCHANT_PAYABLE.name(), null, null, null));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT MERCHANT_PAYABLE:LEGACY 500",
                "CREDIT BANK_CASH:PLATFORM 500");
    }

    @Test
    @DisplayName("必测⑨：REVERSE 红冲产生新交易，历史分录零改动（append-only）")
    void reverseCreatesNewPostingAndNeverTouchesHistory() {
        Wiring w = wiring();
        Posting original = w.engine().post(paymentCapture("PM-ADJ-R1", 10000, 0, 0, "M001", "ALIPAY"));
        List<LedgerEntry> frozenEntries = List.copyOf(original.getEntries());
        long originalId = original.getId();

        Posting red = w.engine().post(adjustment("ADJ-R-1", "REVERSE", 10000, null, null,
                AccountingEventType.PAYMENT_CAPTURE, "PM-ADJ-R1", "M001"));

        assertThat(red.getId()).isNotEqualTo(originalId);
        // 逐行镜像：同账户实例、方向取反、金额不变
        assertThat(entrySummary(w, red)).containsExactly(
                "CREDIT CHANNEL_RECEIVABLE:ALIPAY 10000",
                "DEBIT MERCHANT_PAYABLE:M001 10000");
        // 历史分录未被修改（同一Posting对象仍为原样）
        Posting reread = w.ledger().findById(originalId).orElseThrow();
        assertThat(reread.getEntries()).extracting(e -> e.getId() + ":" + e.getDirection()
                        + ":" + e.getAmountMinor())
                .containsExactlyElementsOf(frozenEntries.stream()
                        .map(e -> e.getId() + ":" + e.getDirection() + ":" + e.getAmountMinor()).toList());
        // 净效果归零
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "ALIPAY")).isZero();
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isZero();
    }

    @Test
    @DisplayName("红冲行经 fixedAccountId 合法触达 LEGACY 实例（更正既有事实 ≠ 新事实）")
    void reversalMayTouchLegacyInstance() {
        Wiring w = wiring();
        long legacyCashId = w.instance(AccountCode.CUSTOMER_CASH, "PLATFORM").getId();
        Posting legacy = Posting.rehydrate(null, null, AccountingEventType.PAYMENT_CAPTURE,
                "PAYMENT_CAPTURE:PM-LEGACY-1", LedgerSourceType.PAYMENT, "PM-LEGACY-1", "CNY",
                null, null, Posting.Status.POSTED,
                List.of(new LedgerEntry(null, legacyCashId, LedgerEntry.Direction.DEBIT, 700, "CNY"),
                        new LedgerEntry(null, w.instance(AccountCode.MERCHANT_PAYABLE, "LEGACY").getId(),
                                LedgerEntry.Direction.CREDIT, 700, "CNY")));
        w.ledger().save(legacy);

        Posting red = w.engine().post(adjustment("ADJ-R-2", "REVERSE", 700, null, null,
                AccountingEventType.PAYMENT_CAPTURE, "PM-LEGACY-1", null));

        assertThat(entrySummary(w, red)).containsExactly(
                "CREDIT CUSTOMER_CASH:PLATFORM 700",
                "DEBIT MERCHANT_PAYABLE:LEGACY 700");
    }

    @Test
    @DisplayName("新事件直接引用 LEGACY 科目 ⇒ LEDGER_ACCOUNT_LEGACY 拒绝")
    void newEventCannotReferenceLegacyAccount() {
        Wiring w = wiring();
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(adjustment("ADJ-LEG-1", "TRANSFER", 100,
                        AccountCode.BANK_CASH.name(), AccountCode.CUSTOMER_CASH.name(), null, null, null)))
                .matches(ex -> ErrorCodes.LEDGER_ACCOUNT_LEGACY.equals(ex.getCode()));
        assertThat(w.ledger().findAllPostings()).isEmpty();
    }

    @Test
    @DisplayName("CORRECT 红蓝字：红冲 + 转账，一笔交易内完成")
    void correctCombinesReversalAndTransfer() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-ADJ-C1", 10000, 0, 0, "M001", "MOCK"));

        Posting blue = w.engine().post(adjustment("ADJ-C-1", "CORRECT", 12000,
                AccountCode.BANK_CASH.name(), AccountCode.MERCHANT_PAYABLE.name(),
                AccountingEventType.PAYMENT_CAPTURE, "PM-ADJ-C1", "M001"));

        assertThat(entrySummary(w, blue)).containsExactly(
                "CREDIT CHANNEL_RECEIVABLE:MOCK 10000",
                "DEBIT MERCHANT_PAYABLE:M001 10000",
                "DEBIT MERCHANT_PAYABLE:M001 12000",
                "CREDIT BANK_CASH:PLATFORM 12000");
    }

    @Test
    @DisplayName("红冲目标不存在 ⇒ NOT_FOUND；from==to / WRITE_OFF ⇒ INVALID_ARGUMENT 或事件门禁")
    void adjustmentGuardrails() {
        Wiring w = wiring();
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(adjustment("ADJ-NF-1", "REVERSE", 100, null, null,
                        AccountingEventType.PAYMENT_CAPTURE, "PM-NOT-EXIST", null)))
                .matches(ex -> ErrorCodes.NOT_FOUND.equals(ex.getCode()));

        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(adjustment("ADJ-WO-1", "WRITE_OFF", 100,
                        AccountCode.SUSPENSE.name(), AccountCode.MERCHANT_PAYABLE.name(), null, null, null)))
                .matches(ex -> ErrorCodes.INVALID_ARGUMENT.equals(ex.getCode()));

        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(adjustment("ADJ-SAME-1", "TRANSFER", 100,
                        AccountCode.SUSPENSE.name(), AccountCode.SUSPENSE.name(), null, null, null)))
                .matches(ex -> ErrorCodes.EVENT_FIELD_MISSING.equals(ex.getCode()));
    }

    @Test
    @DisplayName("调整金额槽位非正 ⇒ EVENT_FIELD_MISSING（requireAmount 兜底）")
    void nonPositiveAdjustmentAmountRefused() {
        Wiring w = wiring();
        AccountingEvent zero = adjustment("ADJ-Z-1", "SUSPEND", 0,
                AccountCode.BANK_CASH.name(), AccountCode.SUSPENSE.name(), null, null, null);
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(zero))
                .matches(ex -> ErrorCodes.EVENT_FIELD_MISSING.equals(ex.getCode()));
    }
}
