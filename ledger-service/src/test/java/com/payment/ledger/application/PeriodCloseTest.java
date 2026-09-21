package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.adjustment;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.LedgerPeriod;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 期间与关账（spec 031 §11 / G2）：关账前置 = 试算平衡 + 无 PENDING；
 * CLOSED 期间对所有新事件关门（含 ADJUSTMENT）。
 */
class PeriodCloseTest {

    private static final String THIS_MONTH =
            YearMonth.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Test
    @DisplayName("关账成功：期间内恒平 ⇒ ledger_periods 置 CLOSED（幂等重复关账回放）")
    void closeBalancedPeriodAndReplay() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-PC-1", 10000, 0, 0, "M001", "ALIPAY"));

        LedgerPeriod closed = w.periodService().close(THIS_MONTH, "finance-bot");
        assertThat(closed.getStatus()).isEqualTo(LedgerPeriod.Status.CLOSED);

        LedgerPeriod again = w.periodService().close(THIS_MONTH, "finance-bot-2");
        assertThat(again.getStatus()).isEqualTo(LedgerPeriod.Status.CLOSED);
    }

    @Test
    @DisplayName("CLOSED 期间拒收一切新事件：PAYMENT_CAPTURE 与 ADJUSTMENT 同门")
    void closedPeriodRejectsAllEvents() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-PC-2", 100, 0, 0, "M001", "MOCK"));
        w.periodService().close(THIS_MONTH, "finance-bot");

        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(paymentCapture("PM-PC-3", 200, 0, 0, "M001", "MOCK")))
                .matches(ex -> ErrorCodes.PERIOD_CLOSED.equals(ex.getCode()));
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(adjustment("ADJ-PC-1", "SUSPEND", 50,
                        AccountCode.BANK_CASH.name(), AccountCode.SUSPENSE.name(), null, null, null)))
                .matches(ex -> ErrorCodes.PERIOD_CLOSED.equals(ex.getCode()));
        assertThat(w.ledger().findAllPostings()).hasSize(1);
    }

    @Test
    @DisplayName("缺行 = OPEN（保守口径）：从未关过的期间可正常记账")
    void absentPeriodTreatedAsOpen() {
        Wiring w = wiring();
        assertThat(w.periods().isClosed("1999-12", "CNY")).isFalse();
        assertThat(w.engine().post(paymentCapture("PM-PC-4", 10, 0, 0, "M001", "WECHAT"))).isNotNull();
    }

    @Test
    @DisplayName("非法期间格式 ⇒ INVALID_ARGUMENT")
    void malformedPeriodRefused() {
        Wiring w = wiring();
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.periodService().close("2026/09", "x"))
                .matches(ex -> ErrorCodes.INVALID_ARGUMENT.equals(ex.getCode()));
    }
}
