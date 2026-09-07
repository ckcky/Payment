package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * 账本仓储 MyBatis 实现：Posting 与其分录在同一短事务内落库（不平衡由聚合根在内存中先拒）。
 */
@Repository
public class MybatisLedgerRepository implements LedgerRepository {

    private final PostingMapper postingMapper;
    private final LedgerEntryMapper entryMapper;

    public MybatisLedgerRepository(PostingMapper postingMapper, LedgerEntryMapper entryMapper) {
        this.postingMapper = postingMapper;
        this.entryMapper = entryMapper;
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
    public List<LedgerEntry> findAllEntries() {
        return entryMapper.selectList(null).stream().map(this::toEntry).toList();
    }

    @Override
    public List<LedgerEntry> findEntriesBySource(LedgerSourceType sourceType, String sourceId) {
        return entryMapper.selectList(
                        Wrappers.<LedgerEntryEntity>lambdaQuery()
                                .eq(LedgerEntryEntity::getSourceType, sourceType.name())
                                .eq(LedgerEntryEntity::getSourceId, sourceId))
                .stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public Posting save(Posting posting) {
        if (posting.getId() == null) {
            PostingEntity entity = toEntity(posting);
            postingMapper.insert(entity);
            List<LedgerEntry> persisted = insertEntries(entity.getId(), posting);
            return Posting.rehydrate(entity.getId(), posting.getPostingNo(), posting.getIdempotencyKey(), posting.getSourceType(),
                    posting.getSourceId(), posting.getCurrency(), posting.getStatus(), persisted);
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
            row.setEntryType(entry.getEntryType().name());
            row.setSourceType(entry.getSourceType().name());
            row.setSourceId(entry.getSourceId());
            row.setCreatedAt(Instant.now());
            entryMapper.insert(row);
            persisted.add(LedgerEntry.rehydrate(row.getId(), postingId, entry.getAccountId(),
                    entry.getDirection(), entry.getAmountMinor(), entry.getCurrency(),
                    entry.getEntryType(), entry.getSourceType(), entry.getSourceId()));
        }
        return List.copyOf(persisted);
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
        return Posting.rehydrate(entity.getId(), entity.getPostingNo(), entity.getIdempotencyKey(),
                LedgerSourceType.valueOf(entity.getSourceType()), entity.getSourceId(),
                entity.getCurrency(), Posting.Status.valueOf(entity.getStatus()), entries);
    }

    private LedgerEntry toEntry(LedgerEntryEntity row) {
        return LedgerEntry.rehydrate(row.getId(), row.getPostingId(), row.getAccountId(),
                LedgerEntry.Direction.valueOf(row.getDirection()), row.getAmountMinor(), row.getCurrency(),
                LedgerEntry.Type.valueOf(row.getEntryType()), LedgerSourceType.valueOf(row.getSourceType()),
                row.getSourceId());
    }

    private PostingEntity toEntity(Posting posting) {
        PostingEntity entity = new PostingEntity();
        entity.setId(posting.getId());
        entity.setPostingNo(posting.getPostingNo());
        entity.setIdempotencyKey(posting.getIdempotencyKey());
        entity.setSourceType(posting.getSourceType().name());
        entity.setSourceId(posting.getSourceId());
        entity.setStatus(posting.getStatus().name());
        entity.setCurrency(posting.getCurrency());
        return entity;
    }
}
