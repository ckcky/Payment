package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;
import com.payment.ledger.domain.posting.PostingRuleRegistry;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * spec 035 / T31：账务完整性信号的可执行断言（A-07/A-10/A-11 告警所挂靠的指标本体）。
 *
 * <p>只测「埋点在正确分支上计数」——门禁语义本身由 031 用例（PostingBalanceGateTest /
 * PeriodCloseTest）覆盖，此处零领域行为变更。</p>
 */
class LedgerObservabilityMetricsTest {

    /** 记录进 map（name|tags → 累计值）的 BusinessMetrics 桩。 */
    static final class RecordingMetrics implements BusinessMetrics {
        final Map<String, Double> counters = new ConcurrentHashMap<>();

        @Override
        public void counter(String name, double value, String... tags) {
            counters.merge(name + "|" + String.join(",", tags), value, Double::sum);
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
        }

        double count(String name, String... tags) {
            return counters.getOrDefault(name + "|" + String.join(",", tags), 0.0);
        }
    }

    private static final String THIS_MONTH =
            YearMonth.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM"));

    @Test
    @DisplayName("构造期不平衡拒绝 ⇒ ledger_unbalanced{currency} 计数（A-10 信号源）")
    void unbalancedRejectionCounted() {
        RecordingMetrics metrics = new RecordingMetrics();
        // 故意用一条展开后借 100 / 贷 99 的规则顶替 PAYMENT_CAPTURE
        // （selfCheck 只查完备性，不查展开结果）
        PostingRule bad = new PostingRule() {
            @Override
            public AccountingEventType eventType() {
                return AccountingEventType.PAYMENT_CAPTURE;
            }

            @Override
            public List<PostingLine> expand(
                    com.payment.ledger.domain.AccountingEvent event,
                    com.payment.ledger.domain.posting.OriginalPostingLookup original) {
                return List.of(
                        PostingLine.debit(AccountCode.BANK_CASH.name(), null, 100),
                        PostingLine.credit(AccountCode.CHANNEL_RECEIVABLE.name(), "MOCK", 99));
            }
        };
        List<PostingRule> rules = new java.util.ArrayList<>(List.of(
                new com.payment.ledger.domain.posting.rules.RefundRule(),
                new com.payment.ledger.domain.posting.rules.ChannelFeeRule(),
                new com.payment.ledger.domain.posting.rules.ChannelSettlementRule(),
                new com.payment.ledger.domain.posting.rules.MerchantSettlementRule(),
                new com.payment.ledger.domain.posting.rules.AdjustmentRule()));
        rules.add(bad);
        Wiring w = wiring(rules, metrics);

        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> w.engine().post(paymentCapture("PM-OBS-1", 100, 0, 0, "M001", "MOCK")))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));

        assertThat(metrics.count("ledger_unbalanced", "module", "ledger", "currency", "CNY"))
                .as("拒绝即计数，且上抛语义不变").isEqualTo(1.0);
    }

    @Test
    @DisplayName("关账试算不平 ⇒ trial_balance_break{currency} + period_close_rejected{reason}（A-10）")
    void trialBreakAtCloseCounted() {
        RecordingMetrics metrics = new RecordingMetrics();
        Wiring w = wiring();
        BalanceChecker rigged = new BalanceChecker(w.ledger(), w.accounts(), w.balances()) {
            @Override
            public Map<String, Long> byCurrency(String period) {
                return Map.of("CNY", 7L); // 人为注入「借-贷 = 7」不平
            }
        };
        PeriodService service = new PeriodService(w.ledger(), rigged, w.periods(), metrics);

        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> service.close(THIS_MONTH, "finance-bot"))
                .matches(ex -> ErrorCodes.LEDGER_UNBALANCED.equals(ex.getCode()));

        assertThat(metrics.count("trial_balance_break", "module", "ledger", "currency", "CNY"))
                .isEqualTo(1.0);
        assertThat(metrics.count("period_close_rejected", "module", "ledger", "reason", "unbalanced"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("正常路径零误计：恒平关账不产生任何 A-10 信号")
    void happyPathCountsNothing() {
        RecordingMetrics metrics = new RecordingMetrics();
        Wiring w = wiring();
        PeriodService service = new PeriodService(w.ledger(), w.balanceChecker(), w.periods(), metrics);
        w.engine().post(paymentCapture("PM-OBS-2", 1000, 0, 0, "M001", "ALIPAY"));

        service.close(THIS_MONTH, "finance-bot");

        assertThat(metrics.counters).isEmpty();
    }
}
