package com.payment.ledger.infra;

import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存账本仓储：仅用于领域/应用单测（不走 Spring 注入），生产由 {@code MybatisLedgerRepository} 承接。
 */
public class InMemoryLedgerRepository implements LedgerRepository {

    private final Map<Long, Posting> byId = new LinkedHashMap<>();
    private final AtomicLong postingIdGen = new AtomicLong();
    private final AtomicLong entryIdGen = new AtomicLong();

    @Override
    public Optional<Posting> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Posting> findByIdempotencyKey(String idempotencyKey) {
        return byId.values().stream()
                .filter(p -> idempotencyKey.equals(p.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public List<Posting> findBySource(LedgerSourceType sourceType, String sourceId) {
        return byId.values().stream()
                .filter(p -> p.getSourceType() == sourceType && sourceId.equals(p.getSourceId()))
                .toList();
    }

    @Override
    public List<LedgerEntry> findAllEntries() {
        List<LedgerEntry> all = new ArrayList<>();
        byId.values().forEach(p -> all.addAll(p.getEntries()));
        return List.copyOf(all);
    }

    @Override
    public List<LedgerEntry> findEntriesBySource(LedgerSourceType sourceType, String sourceId) {
        return findAllEntries().stream()
                .filter(e -> e.getSourceType() == sourceType && sourceId.equals(e.getSourceId()))
                .toList();
    }

    @Override
    public Posting save(Posting posting) {
        Posting stored = posting;
        if (stored.getId() == null) {
            List<LedgerEntry> persisted = new ArrayList<>();
            Posting withId = Posting.rehydrate(postingIdGen.incrementAndGet(), stored.getPostingNo(),
                    stored.getIdempotencyKey(), stored.getSourceType(), stored.getSourceId(),
                    stored.getCurrency(), stored.getStatus(), stored.getEntries());
            for (LedgerEntry entry : withId.getEntries()) {
                LedgerEntry copy = LedgerEntry.rehydrate(entryIdGen.incrementAndGet(), withId.getId(),
                        entry.getAccountId(), entry.getDirection(), entry.getAmountMinor(), entry.getCurrency(),
                        entry.getEntryType(), entry.getSourceType(), entry.getSourceId());
                persisted.add(copy);
            }
            stored = Posting.rehydrate(withId.getId(), withId.getPostingNo(), withId.getIdempotencyKey(),
                    withId.getSourceType(), withId.getSourceId(), withId.getCurrency(), withId.getStatus(),
                    persisted);
        }
        byId.put(stored.getId(), stored);
        return stored;
    }
}
