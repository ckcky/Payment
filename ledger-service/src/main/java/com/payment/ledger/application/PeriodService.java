package com.payment.ledger.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.ledger.domain.LedgerPeriod;
import com.payment.ledger.domain.LedgerPeriodRepository;
import com.payment.ledger.domain.LedgerRepository;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 期间与关账（spec 031 §11，G2）。
 *
 * <p>关账前置校验 = 该期间试算平衡（按币种）且无 {@code PENDING} 交易残留；
 * CLOSED 期间拒收一切新事件（含 ADJUSTMENT，门禁在 {@link PostingEngine}）。
 * 币种口径：对「该期间出现过发生额」的每个币种逐一校验。</p>
 */
@Service
public class PeriodService {

    private final LedgerRepository ledgerRepository;
    private final BalanceChecker balanceChecker;
    private final LedgerPeriodRepository periodRepository;

    public PeriodService(LedgerRepository ledgerRepository,
                         BalanceChecker balanceChecker,
                         LedgerPeriodRepository periodRepository) {
        this.ledgerRepository = ledgerRepository;
        this.balanceChecker = balanceChecker;
        this.periodRepository = periodRepository;
    }

    /** 关账（幂等：已 CLOSED 直接回放）。 */
    public LedgerPeriod close(String period, String closedBy) {
        requireValidPeriod(period);
        if (periodRepository.find(period, "CNY").map(p -> p.getStatus() == LedgerPeriod.Status.CLOSED)
                .orElse(false)) {
            return periodRepository.find(period, "CNY").orElseThrow();
        }
        if (ledgerRepository.existsPendingPosting(period)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "period has PENDING postings, close refused: " + period);
        }
        Map<String, Long> diffs = balanceChecker.byCurrency(period);
        for (Map.Entry<String, Long> entry : diffs.entrySet()) {
            if (entry.getValue() != 0L) {
                throw BizException.of(ErrorCodes.LEDGER_UNBALANCED,
                        "period trial balance not balanced (" + entry.getKey() + " diff=" + entry.getValue()
                                + "), close refused: " + period);
            }
        }
        List<String> currencies = diffs.isEmpty() ? List.of("CNY") : List.copyOf(diffs.keySet());
        for (String currency : currencies) {
            periodRepository.close(period, currency, closedBy);
        }
        return periodRepository.find(period, currencies.get(0)).orElseThrow();
    }

    public List<LedgerPeriod> list() {
        return periodRepository.findAll();
    }

    /** 关账按 (period, currency) 独立；MVP 仅 CNY，空期间默认按 CNY 建行。 */
    private void requireValidPeriod(String period) {
        try {
            YearMonth.parse(period);
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "period must be YYYY-MM: " + period);
        }
    }
}
