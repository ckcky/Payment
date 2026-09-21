package com.payment.ledger.infra;

import com.payment.ledger.domain.AccountBalance;
import com.payment.ledger.domain.AccountBalanceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存余额投影（仅单测装配用）：累加口径与 MyBatis 实现一致（原子累加视图）。
 */
public class InMemoryAccountBalanceRepository implements AccountBalanceRepository {

    private final Map<String, AccountBalance> byKey = new ConcurrentHashMap<>();

    /** 供内存记账事务内累加（对应 MybatisLedgerRepository 的投影步骤）。 */
    public void accumulate(long accountId, String currency, long debitDelta, long creditDelta,
                           long entryCount, long lastEntryId) {
        byKey.compute(accountId + "|" + currency, (k, prev) -> {
            long debit = prev == null ? 0 : prev.getDebitTotal();
            long credit = prev == null ? 0 : prev.getCreditTotal();
            long count = prev == null ? 0 : prev.getEntryCount();
            long last = prev == null ? 0 : prev.getLastEntryId();
            return new AccountBalance(accountId, currency, debit + debitDelta, credit + creditDelta,
                    count + entryCount, Math.max(last, lastEntryId));
        });
    }

    @Override
    public Optional<AccountBalance> find(long accountInstanceId, String currency) {
        return Optional.ofNullable(byKey.get(accountInstanceId + "|" + currency));
    }

    @Override
    public List<AccountBalance> findAll() {
        return byKey.values().stream()
                .sorted(java.util.Comparator.comparingLong(AccountBalance::getAccountInstanceId))
                .toList();
    }

    @Override
    public int rebuild() {
        return byKey.size();
    }
}
