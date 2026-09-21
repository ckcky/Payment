package com.payment.ledger.infra;

import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.domain.TrialBalanceRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存账本仓储：仅用于领域/应用单测（不走 Spring 注入），生产由 {@code MybatisLedgerRepository} 承接。
 *
 * <p>save 同步累加余额投影（与 MyBatis 实现的同事务语义对齐）；幂等回查、事件回查与试算
 * 聚合的口径均与真实现一致。</p>
 */
public class InMemoryLedgerRepository implements LedgerRepository {

    private final Map<Long, Posting> byId = new LinkedHashMap<>();
    private final AtomicLong postingIdGen = new AtomicLong();
    private final AtomicLong entryIdGen = new AtomicLong();
    private final InMemoryAccountBalanceRepository balances;

    public InMemoryLedgerRepository() {
        this(new InMemoryAccountBalanceRepository());
    }

    public InMemoryLedgerRepository(InMemoryAccountBalanceRepository balances) {
        this.balances = balances;
    }

    public InMemoryAccountBalanceRepository balances() {
        return balances;
    }

    @Override
    public synchronized Optional<Posting> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized Optional<Posting> findByIdempotencyKey(String idempotencyKey) {
        return byId.values().stream()
                .filter(p -> idempotencyKey.equals(p.getIdempotencyKey()))
                .findFirst();
    }

    @Override
    public synchronized Optional<Posting> findByEvent(AccountingEventType eventType, String sourceId) {
        return byId.values().stream()
                .filter(p -> p.getEventType() == eventType && sourceId.equals(p.getSourceId()))
                .findFirst();
    }

    @Override
    public synchronized List<Posting> findBySource(LedgerSourceType sourceType, String sourceId) {
        return byId.values().stream()
                .filter(p -> p.getSourceType() == sourceType && sourceId.equals(p.getSourceId()))
                .toList();
    }

    @Override
    public synchronized List<Posting> findAllPostings() {
        return byId.values().stream()
                .sorted(java.util.Comparator.comparingLong(Posting::getId).reversed())
                .toList();
    }

    @Override
    public synchronized List<TrialBalanceRow> trialBalance(String period) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (Posting posting : byId.values()) {
            if (period != null && !period.equals(posting.getPeriod())) {
                continue;
            }
            for (LedgerEntry entry : posting.getEntries()) {
                String key = entry.getAccountId() + "|" + entry.getCurrency();
                long[] sums = agg.computeIfAbsent(key, k -> new long[2]);
                if (entry.getDirection() == LedgerEntry.Direction.DEBIT) {
                    sums[0] += entry.getAmountMinor();
                } else {
                    sums[1] += entry.getAmountMinor();
                }
            }
        }
        return agg.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("\\|");
                    return new TrialBalanceRow(Long.parseLong(parts[0]), parts[1],
                            e.getValue()[0], e.getValue()[1]);
                })
                .toList();
    }

    @Override
    public synchronized boolean existsPendingPosting(String period) {
        return byId.values().stream().anyMatch(p -> period.equals(p.getPeriod())
                && p.getStatus() == Posting.Status.PENDING);
    }

    @Override
    public synchronized Posting save(Posting posting) {
        if (posting.getId() == null) {
            long postingId = postingIdGen.incrementAndGet();
            List<LedgerEntry> persisted = new ArrayList<>();
            Map<String, long[]> deltas = new LinkedHashMap<>();
            for (LedgerEntry entry : posting.getEntries()) {
                long entryId = entryIdGen.incrementAndGet();
                LedgerEntry copy = LedgerEntry.rehydrate(entryId, postingId, entry.getAccountId(),
                        entry.getDirection(), entry.getAmountMinor(), entry.getCurrency());
                persisted.add(copy);
                String key = entry.getAccountId() + "|" + entry.getCurrency();
                long[] d = deltas.computeIfAbsent(key, k -> new long[4]);
                if (entry.getDirection() == LedgerEntry.Direction.DEBIT) {
                    d[0] += entry.getAmountMinor();
                } else {
                    d[1] += entry.getAmountMinor();
                }
                d[2] += 1;
                d[3] = Math.max(d[3], entryId);
            }
            Posting saved = Posting.rehydrate(postingId, posting.getPostingNo(), posting.getEventType(),
                    posting.getIdempotencyKey(), posting.getSourceType(), posting.getSourceId(),
                    posting.getCurrency(), posting.getPeriod(), posting.getPostedAt(),
                    posting.getStatus(), List.copyOf(persisted));
            byId.put(postingId, saved);
            for (Map.Entry<String, long[]> e : deltas.entrySet()) {
                String[] parts = e.getKey().split("\\|");
                long[] d = e.getValue();
                balances.accumulate(Long.parseLong(parts[0]), parts[1], d[0], d[1], d[2], d[3]);
            }
            return saved;
        }
        byId.put(posting.getId(), posting);
        return posting;
    }
}
