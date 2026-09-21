package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.domain.TrialBalanceRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账本仓储 MyBatis 实现：交易、分录与**余额投影**在同一本地事务内落库
 * （spec 031 §10 构造性保证——投影与分录永不漂移）；失败整体回滚、不留半套分录。
 *
 * <p>幂等：{@code uk_postings_idempotency_key}（派生键）与 {@code uk_event_source}
 * （事件级）双唯一约束（原则 10）。</p>
 */
@Repository
public class MybatisLedgerRepository implements LedgerRepository {

    private final PostingMapper postingMapper;
    private final LedgerEntryMapper entryMapper;
    private final AccountBalanceMapper balanceMapper;

    public MybatisLedgerRepository(PostingMapper postingMapper,
                                   LedgerEntryMapper entryMapper,
                                   AccountBalanceMapper balanceMapper) {
        this.postingMapper = postingMapper;
        this.entryMapper = entryMapper;
        this.balanceMapper = balanceMapper;
    }

    @Override
    public Optional<Posting> findById(Long id) {
        PostingEntity entity = postingMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Posting> findByIdempotencyKey(String idempotencyKey) {
        PostingEntity entity = postingMapper.selectOne(
                Wrappers.<PostingEntity>lambdaQuery().eq(PostingEntity::getIdempotencyKey, idempotencyKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<Posting> findByEvent(AccountingEventType eventType, String sourceId) {
        PostingEntity entity = postingMapper.selectOne(
                Wrappers.<PostingEntity>lambdaQuery()
                        .eq(PostingEntity::getEventType, eventType.name())
                        .eq(PostingEntity::getSourceId, sourceId));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<Posting> findBySource(LedgerSourceType sourceType, String sourceId) {
        return postingMapper.selectList(
                        Wrappers.<PostingEntity>lambdaQuery()
                                .eq(PostingEntity::getSourceType, sourceType.name())
                                .eq(PostingEntity::getSourceId, sourceId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Posting> findAllPostings() {
        // 两步式批量加载（修 N+1）：全量读路径逐笔查 entries 会让 871 笔账本要 1s+，
        // 调用方（recon 审计建批）贴着 Feign read-timeout 上限，数据一涨即超时
        List<PostingEntity> entities = postingMapper.selectList(
                        Wrappers.<PostingEntity>lambdaQuery().orderByDesc(PostingEntity::getId))
                .stream()
                .limit(1000)
                .toList();
        if (entities.isEmpty()) {
            return List.of();
        }
        List<Long> ids = entities.stream().map(PostingEntity::getId).toList();
        Map<Long, List<LedgerEntry>> entriesByPostingId = entryMapper.selectList(
                        Wrappers.<LedgerEntryEntity>lambdaQuery().in(LedgerEntryEntity::getPostingId, ids))
                .stream()
                .map(this::toEntry)
                .collect(Collectors.groupingBy(LedgerEntry::getPostingId));
        return entities.stream()
                .map(e -> toDomain(e, entriesByPostingId.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    @Override
    public List<TrialBalanceRow> trialBalance(String period) {
        return entryMapper.trialBalance(period);
    }

    @Override
    public boolean existsPendingPosting(String period) {
        return postingMapper.selectCount(
                Wrappers.<PostingEntity>lambdaQuery()
                        .eq(PostingEntity::getPeriod, period)
                        .eq(PostingEntity::getStatus, Posting.Status.PENDING.name())) > 0;
    }

    @Override
    @Transactional
    public Posting save(Posting posting) {
        if (posting.getId() == null) {
            PostingEntity entity = toEntity(posting);
            postingMapper.insert(entity);
            List<LedgerEntry> persisted = insertEntries(entity.getId(), posting);
            projectBalances(persisted);
            return Posting.rehydrate(entity.getId(), posting.getPostingNo(), posting.getEventType(),
                    posting.getIdempotencyKey(), posting.getSourceType(), posting.getSourceId(),
                    posting.getCurrency(), posting.getPeriod(), posting.getPostedAt(),
                    posting.getStatus(), persisted);
        }
        PostingEntity entity = toEntity(posting);
        postingMapper.updateById(entity);
        return posting;
    }

    private List<LedgerEntry> insertEntries(Long postingId, Posting posting) {
        List<LedgerEntry> persisted = new ArrayList<>();
        for (LedgerEntry entry : posting.getEntries()) {
            LedgerEntryEntity row = new LedgerEntryEntity();
            row.setPostingId(postingId);
            row.setAccountId(entry.getAccountId());
            row.setDirection(entry.getDirection().name());
            row.setAmountMinor(entry.getAmountMinor());
            row.setCurrency(entry.getCurrency());
            // 031：entry_type / source_type / source_id 停写（停读走 postings join），列保留为 NULL
            row.setCreatedAt(Instant.now());
            entryMapper.insert(row);
            persisted.add(LedgerEntry.rehydrate(row.getId(), postingId, entry.getAccountId(),
                    entry.getDirection(), entry.getAmountMinor(), entry.getCurrency()));
        }
        return List.copyOf(persisted);
    }

    /** 余额投影原子累加：同账户多行先聚合再单条累加（避免间隙死锁，§10）。 */
    private void projectBalances(List<LedgerEntry> entries) {
        record Delta(long debit, long credit, long count, long lastEntryId) {
            Delta add(LedgerEntry e) {
                boolean debitSide = e.getDirection() == LedgerEntry.Direction.DEBIT;
                return new Delta(debit + (debitSide ? e.getAmountMinor() : 0),
                        credit + (debitSide ? 0 : e.getAmountMinor()),
                        count + 1, Math.max(lastEntryId, e.getId() == null ? 0 : e.getId()));
            }
        }
        Map<String, Delta> aggregated = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            String key = entry.getAccountId() + "|" + entry.getCurrency();
            Delta prev = aggregated.getOrDefault(key, new Delta(0, 0, 0, 0));
            aggregated.put(key, prev.add(entry));
        }
        Instant now = Instant.now();
        for (Map.Entry<String, Delta> e : aggregated.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            long accountId = Long.parseLong(parts[0]);
            String currency = parts[1];
            Delta d = e.getValue();
            int updated = balanceMapper.accumulate(accountId, currency, d.debit(), d.credit(),
                    d.count(), d.lastEntryId(), now);
            if (updated == 0) {
                try {
                    balanceMapper.insertInitial(accountId, currency, d.debit(), d.credit(),
                            d.count(), d.lastEntryId(), now);
                } catch (DuplicateKeyException race) {
                    // 并发首记同账户：对方 insert 已提交/先行，重试累加即可
                    balanceMapper.accumulate(accountId, currency, d.debit(), d.credit(),
                            d.count(), d.lastEntryId(), now);
                }
            }
        }
    }

    private Posting toDomain(PostingEntity entity) {
        return toDomain(entity, entryMapper.selectList(
                        Wrappers.<LedgerEntryEntity>lambdaQuery()
                                .eq(LedgerEntryEntity::getPostingId, entity.getId()))
                .stream()
                .map(this::toEntry)
                .toList());
    }

    private Posting toDomain(PostingEntity entity, List<LedgerEntry> entries) {
        return Posting.rehydrate(entity.getId(), entity.getPostingNo(),
                AccountingEventType.valueOf(entity.getEventType()), entity.getIdempotencyKey(),
                LedgerSourceType.valueOf(entity.getSourceType()), entity.getSourceId(),
                entity.getCurrency(), entity.getPeriod(), entity.getPostedAt(),
                Posting.Status.valueOf(entity.getStatus()), entries);
    }

    private LedgerEntry toEntry(LedgerEntryEntity row) {
        return LedgerEntry.rehydrate(row.getId(), row.getPostingId(), row.getAccountId(),
                LedgerEntry.Direction.valueOf(row.getDirection()), row.getAmountMinor(),
                row.getCurrency());
    }

    private PostingEntity toEntity(Posting posting) {
        PostingEntity entity = new PostingEntity();
        entity.setId(posting.getId());
        entity.setPostingNo(posting.getPostingNo());
        entity.setEventType(posting.getEventType().name());
        entity.setIdempotencyKey(posting.getIdempotencyKey());
        entity.setSourceType(posting.getSourceType().name());
        entity.setSourceId(posting.getSourceId());
        entity.setStatus(posting.getStatus().name());
        entity.setCurrency(posting.getCurrency());
        entity.setPeriod(posting.getPeriod());
        entity.setPostedAt(posting.getPostedAt());
        return entity;
    }
}
