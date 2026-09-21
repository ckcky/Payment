package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.entrySummary;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.Posting;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 必测 ①②③④（spec 031 §7.1 案例六）：PAYMENT_CAPTURE gross=100.00 / merchantFee=0.80 /
 * channelFee=0.60 / M001 / ALIPAY —— 逐行断言五分录与 §2 终态余额投影。
 */
class PaymentCapturePostingTest {

    @Test
    @DisplayName("必测①②③：毛额/商户费/渠道费三分支逐行展开 [§7.1]")
    void caseSixExpandsFiveEntries() {
        Wiring w = wiring();
        Posting posting = w.engine().post(
                paymentCapture("PM-CASE6-001", 10000, 80, 60, "M001", "ALIPAY"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT CHANNEL_RECEIVABLE:ALIPAY 10000",
                "CREDIT MERCHANT_PAYABLE:M001 10000",
                "DEBIT MERCHANT_PAYABLE:M001 80",
                "CREDIT FEE_REVENUE:PLATFORM 80",
                "DEBIT CHANNEL_FEE_EXPENSE:ALIPAY 60",
                "CREDIT CHANNEL_RECEIVABLE:ALIPAY 60");
    }

    @Test
    @DisplayName("必测④：案例六终态余额 Channel Receivable=99.40 / Merchant Payable=99.20 / Fee Revenue=0.80 / Channel Fee Expense=0.60")
    void caseSixFinalBalances() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-CASE6-002", 10000, 80, 60, "M001", "ALIPAY"));

        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "ALIPAY"))
                .as("渠道应收 = 100.00 - 0.60 = 99.40（分）").isEqualTo(9940);
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001"))
                .as("应付商户 = 100.00 - 0.80 = 99.20（分，贷余）").isEqualTo(9920);
        assertThat(w.signedBalance(AccountCode.FEE_REVENUE, "PLATFORM"))
                .as("手续费收入 = 0.80（分，贷余）").isEqualTo(80);
        assertThat(w.signedBalance(AccountCode.CHANNEL_FEE_EXPENSE, "ALIPAY"))
                .as("渠道费成本 = 0.60（分）").isEqualTo(60);
    }

    @Test
    @DisplayName("费槽位为 0（过渡期上游口径 §8）：只产生毛额两行，不落 0 金额分录")
    void zeroFeesProduceGrossLinesOnly() {
        Wiring w = wiring();
        Posting posting = w.engine().post(
                paymentCapture("PM-CASE6-003", 10000, 0, 0, "M001", "MOCK"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT CHANNEL_RECEIVABLE:MOCK 10000",
                "CREDIT MERCHANT_PAYABLE:M001 10000");
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "MOCK")).isEqualTo(10000);
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(10000);
    }

    @Test
    @DisplayName("商户首笔事件自动开立应付实例（§5.4）；多商户分户互不串账")
    void merchantInstancesAreOpenedOnDemand() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-M-A", 5000, 0, 0, "MA", "ALIPAY"));
        w.engine().post(paymentCapture("PM-M-B", 3000, 0, 0, "MB", "ALIPAY"));

        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "MA")).isEqualTo(5000);
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "MB")).isEqualTo(3000);
        // 渠道应收是两笔的合并口径
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "ALIPAY")).isEqualTo(8000);
    }

    @Test
    @DisplayName("同商户多笔支付累加到同一账户实例（1:N 交易 → 1 户）")
    void multiplePaymentsAccumulateOnOneInstance() {
        Wiring w = wiring();
        List<Posting> postings = List.of(
                w.engine().post(paymentCapture("PM-ACC-1", 1000, 0, 0, "M001", "WECHAT")),
                w.engine().post(paymentCapture("PM-ACC-2", 2500, 0, 0, "M001", "WECHAT")));

        assertThat(postings).extracting(Posting::getId).doesNotHaveDuplicates();
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(3500);
        assertThat(w.balanceView(AccountCode.CHANNEL_RECEIVABLE, "WECHAT").entryCount()).isEqualTo(2);
    }
}
