package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.entrySummary;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.refund;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.Posting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REFUND 规则（spec 031 §7.2）：退款冲减应收/应付，不再触碰 LEGACY 现金科目；
 * 费用退还仅当上游确认（Ledger 不猜）。
 */
class RefundPostingTest {

    @Test
    @DisplayName("退款：Dr MERCHANT_PAYABLE / Cr CHANNEL_RECEIVABLE（毛额对冲）")
    void refundReducesPayableAndReceivable() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-RF-1", 10000, 0, 0, "M001", "ALIPAY"));

        Posting posting = w.engine().post(refund("RF-RF-1", 4000, 0, 0, "M001", "ALIPAY"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT MERCHANT_PAYABLE:M001 4000",
                "CREDIT CHANNEL_RECEIVABLE:ALIPAY 4000");
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(6000);
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "ALIPAY")).isEqualTo(6000);
    }

    @Test
    @DisplayName("确认退费的退款：收入红字 + 成本红字（§7.2 rule 2/3）")
    void refundWithConfirmedFeeReturn() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-RF-2", 10000, 80, 60, "M001", "ALIPAY"));

        Posting posting = w.engine().post(refund("RF-RF-2", 5000, 40, 30, "M001", "ALIPAY"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT MERCHANT_PAYABLE:M001 5000",
                "CREDIT CHANNEL_RECEIVABLE:ALIPAY 5000",
                "DEBIT FEE_REVENUE:PLATFORM 40",
                "CREDIT MERCHANT_PAYABLE:M001 40",
                "DEBIT CHANNEL_RECEIVABLE:ALIPAY 30",
                "CREDIT CHANNEL_FEE_EXPENSE:ALIPAY 30");
        // 终态：应收 10000-60-5000+30=4970；应付 10000-80-5000+40=4960；收入 80-40=40；成本 60-30=30
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "ALIPAY")).isEqualTo(4970);
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(4960);
        assertThat(w.signedBalance(AccountCode.FEE_REVENUE, "PLATFORM")).isEqualTo(40);
        assertThat(w.signedBalance(AccountCode.CHANNEL_FEE_EXPENSE, "ALIPAY")).isEqualTo(30);
    }

    @Test
    @DisplayName("退款不引用 CUSTOMER_CASH：LEGACY 科目被新事件拒绝的口径由 resolver 兜底，规则本身不再产生现金腿")
    void refundNeverTouchesLegacyCash() {
        Wiring w = wiring();
        Posting posting = w.engine().post(refund("RF-RF-3", 100, 0, 0, "M001", "MOCK"));

        assertThat(posting.getEntries()).allSatisfy(e -> assertThat(
                w.accounts().findInstanceById(e.getAccountId()).orElseThrow().getDefinitionCode())
                .isNotEqualTo("CUSTOMER_CASH"));
    }

    @Test
    @DisplayName("缺 merchantId / channelCode 的退款事件 fail fast（EVENT_FIELD_MISSING，不猜默认）")
    void refundMissingSlotsFailsFast() {
        Wiring w = wiring();
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(
                        new com.payment.ledger.domain.AccountingEvent(
                                com.payment.common.dto.rpc.AccountingEventType.REFUND,
                                com.payment.ledger.domain.LedgerSourceType.REFUND,
                                "RF-BAD-1", "CNY", 100L, 0L, 0L, null, "ALIPAY",
                                null, null, null, null, null, null, null)))
                .matches(ex -> ErrorCodes.EVENT_FIELD_MISSING.equals(ex.getCode()));
        assertThat(w.ledger().findAllPostings()).isEmpty();
    }
}
