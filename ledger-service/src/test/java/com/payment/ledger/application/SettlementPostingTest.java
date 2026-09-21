package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.channelFee;
import static com.payment.ledger.LedgerTestSupport.channelSettlement;
import static com.payment.ledger.LedgerTestSupport.entrySummary;
import static com.payment.ledger.LedgerTestSupport.merchantSettlement;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
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
 * 结算两条规则（spec 031 §7.4 / §7.5 + H3）：渠道清算到账核销应收；
 * 商户结算应付 → 已结算待出款（负净额反向、零净额拒绝）。
 */
class SettlementPostingTest {

    @Test
    @DisplayName("MERCHANT_SETTLEMENT net>0：Dr 应付 / Cr 已结算待出款（同商户结转）")
    void positiveNetCarriesPayableToSettled() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-ST-1", 10000, 80, 0, "M001", "ALIPAY"));

        Posting posting = w.engine().post(merchantSettlement("BS-ST-1", 9920, "M001"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT MERCHANT_PAYABLE:M001 9920",
                "CREDIT SETTLEMENT_PAYABLE:M001 9920");
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isZero();
        assertThat(w.signedBalance(AccountCode.SETTLEMENT_PAYABLE, "M001")).isEqualTo(9920);
    }

    @Test
    @DisplayName("MERCHANT_SETTLEMENT net<0（H3/C-07）：反向结转，商户倒欠进入下期")
    void negativeNetReversesDirection() {
        Wiring w = wiring();
        Posting posting = w.engine().post(merchantSettlement("BS-ST-NEG", -1500, "M001"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT SETTLEMENT_PAYABLE:M001 1500",
                "CREDIT MERCHANT_PAYABLE:M001 1500");
        // 贷余科目被借记 → 正常余额取号为负（倒欠显式可见，替代「net≤0 不记账」的科目残留）
        assertThat(w.signedBalance(AccountCode.SETTLEMENT_PAYABLE, "M001")).isEqualTo(-1500);
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(1500);
    }

    @Test
    @DisplayName("MERCHANT_SETTLEMENT net=0：拒绝（空批次不发事件；账本兜底不记空交易）")
    void zeroNetIsRefused() {
        Wiring w = wiring();
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(merchantSettlement("BS-ST-0", 0, "M001")))
                .matches(ex -> ErrorCodes.EVENT_FIELD_MISSING.equals(ex.getCode()));
        assertThat(w.ledger().findAllPostings()).isEmpty();
    }

    @Test
    @DisplayName("CHANNEL_SETTLEMENT：钱从渠道进平台银行户，Dr BANK_CASH / Cr 渠道应收（净额）")
    void channelSettlementLiquidatesReceivable() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-CS-1", 10000, 0, 60, "M001", "WECHAT"));

        Posting posting = w.engine().post(channelSettlement("CST-1", 9940, "WECHAT"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT BANK_CASH:PLATFORM 9940",
                "CREDIT CHANNEL_RECEIVABLE:WECHAT 9940");
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "WECHAT")).isZero();
        assertThat(w.signedBalance(AccountCode.BANK_CASH, "PLATFORM")).isEqualTo(9940);
    }

    @Test
    @DisplayName("CHANNEL_FEE 独立成本事件（§7.3）：Dr 渠道费成本 / Cr 渠道应收")
    void standaloneChannelFeeHitsReceivable() {
        Wiring w = wiring();
        Posting posting = w.engine().post(channelFee("FEE-DOUYIN-1", 250, "DOUYIN"));

        assertThat(entrySummary(w, posting)).containsExactly(
                "DEBIT CHANNEL_FEE_EXPENSE:DOUYIN 250",
                "CREDIT CHANNEL_RECEIVABLE:DOUYIN 250");
        assertThat(w.signedBalance(AccountCode.CHANNEL_FEE_EXPENSE, "DOUYIN")).isEqualTo(250);
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "DOUYIN")).isEqualTo(-250);
    }
}
