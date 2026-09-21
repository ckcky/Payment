package com.payment.ledger.infra;

import com.payment.ledger.domain.LedgerPeriod;
import com.payment.ledger.domain.LedgerPeriodRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存期间表（仅单测装配用）：缺行 = OPEN 与 MyBatis 实现同口径。
 */
public class InMemoryLedgerPeriodRepository implements LedgerPeriodRepository {

    private final Map<String, LedgerPeriod> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<LedgerPeriod> find(String period, String currency) {
        return Optional.ofNullable(byKey.get(period + "|" + currency));
    }

    @Override
    public List<LedgerPeriod> findAll() {
        return List.copyOf(byKey.values());
    }

    @Override
    public void close(String period, String currency, String closedBy) {
        byKey.put(period + "|" + currency,
                new LedgerPeriod(period, currency, LedgerPeriod.Status.CLOSED, Instant.now(), closedBy));
    }
}
