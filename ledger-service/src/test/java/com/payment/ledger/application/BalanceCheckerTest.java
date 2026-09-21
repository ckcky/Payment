package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.merchantSettlement;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.refund;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.LedgerTestSupport.Wiring;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BalanceChecker（spec 031 §10 / FR-007，G1/G2）：试算恒按分录聚合；投影只作展示读模型。
 */
class BalanceCheckerTest {

    private Wiring w;
    private String currentPeriod;

    @BeforeEach
    void seed() {
        w = wiring();
        w.engine().post(paymentCapture("PM-BC-1", 10000, 80, 60, "M001", "ALIPAY"));
        w.engine().post(refund("RF-BC-1", 4000, 0, 0, "M001", "ALIPAY"));
        w.engine().post(merchantSettlement("BS-BC-1", 5000, "M001"));
        currentPeriod = YearMonth.now(ZoneId.systemDefault()).toString();
    }

    @Test
    @DisplayName("全局试算平衡：任意事件组合后 Σ借 == Σ贷（按币种）")
    void globalTrialBalancesToZero() {
        assertThat(w.balanceChecker().isBalanced()).isTrue();
        assertThat(w.balanceChecker().byCurrency()).containsEntry("CNY", 0L);
    }

    @Test
    @DisplayName("G2：期间过滤的发生额同样恒平")
    void periodScopedDiffIsZero() {
        assertThat(w.balanceChecker().byCurrency(currentPeriod)).containsEntry("CNY", 0L);
        assertThat(w.balanceChecker().byCurrency("2000-01")).isEmpty();
    }

    @Test
    @DisplayName("试算平衡表按账户实例 × 币种出行，取号按正常余额方向")
    void trialBalanceRowsAreSigned() {
        var rows = w.balanceChecker().trialBalance(null);

        var cr = row(rows, AccountCode.CHANNEL_RECEIVABLE, "ALIPAY");
        assertThat(cr.debitTotal()).isEqualTo(10000);
        assertThat(cr.creditTotal()).isEqualTo(60 + 4000);
        assertThat(cr.balance()).isEqualTo(10000 - 4060);

        var mp = row(rows, AccountCode.MERCHANT_PAYABLE, "M001");
        assertThat(mp.balance()).as("贷余 = 10000 - (80 费 + 4000 退 + 5000 结算)").isEqualTo(920);
    }

    @Test
    @DisplayName("投影 = 分录聚合的镜像：balanceOf 与 trialBalance 取号一致，缺行=零余额")
    void projectionMirrorsEntries() {
        var view = w.balanceView(AccountCode.FEE_REVENUE, "PLATFORM");
        assertThat(view.debitTotal()).isZero();
        assertThat(view.creditTotal()).isEqualTo(80);
        assertThat(view.balance()).isEqualTo(80);
        assertThat(view.entryCount()).isEqualTo(1);

        assertThat(w.balanceChecker().balanceOf(
                w.instance(AccountCode.SUSPENSE, "PLATFORM").getId(), "CNY").balance()).isZero();
    }

    @Test
    @DisplayName("balances(currency) 覆盖全部实例（含未发生户，零余额可见）")
    void balancesListsAllInstances() {
        assertThat(w.balanceChecker().balances("CNY"))
                .extracting(v -> v.accountCode() + ":" + v.ownerId())
                .contains("CHANNEL_RECEIVABLE:ALIPAY", "MERCHANT_PAYABLE:M001",
                        "FEE_REVENUE:PLATFORM", "SUSPENSE:PLATFORM");
    }

    private com.payment.ledger.application.BalanceChecker.TrialBalanceView row(
            java.util.List<com.payment.ledger.application.BalanceChecker.TrialBalanceView> rows,
            AccountCode code, String ownerId) {
        return rows.stream()
                .filter(r -> r.accountCode().equals(code.name()) && r.ownerId().equals(ownerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("trial balance row missing: " + code + ":" + ownerId));
    }
}
