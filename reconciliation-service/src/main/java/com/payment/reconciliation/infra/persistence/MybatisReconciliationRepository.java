package com.payment.reconciliation.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.Match;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationRepository;
import com.payment.reconciliation.domain.ReconciliationStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对账批次仓储 MyBatis 实现：批次落到自有 Schema，匹配/差异以 JSON 内嵌随聚合读写。
 *
 * <p>更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖。</p>
 */
@Repository
public class MybatisReconciliationRepository implements ReconciliationRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Match>> MATCH_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Difference>> DIFFERENCE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<ChannelStatementSource> SOURCE = new TypeReference<>() {
    };

    private final ReconciliationBatchMapper mapper;

    public MybatisReconciliationRepository(ReconciliationBatchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ReconciliationBatch> findById(Long id) {
        ReconciliationBatchEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<ReconciliationBatch> findByPeriod(String period) {
        ReconciliationBatchEntity entity = mapper.selectOne(
                Wrappers.<ReconciliationBatchEntity>lambdaQuery()
                        .eq(ReconciliationBatchEntity::getPeriod, period));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<ReconciliationBatch> findByPeriodBetween(String from, String to) {
        return mapper.selectList(
                        Wrappers.<ReconciliationBatchEntity>lambdaQuery()
                                .ge(ReconciliationBatchEntity::getPeriod, from)
                                .le(ReconciliationBatchEntity::getPeriod, to)
                                .orderByAsc(ReconciliationBatchEntity::getPeriod))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public ReconciliationBatch save(ReconciliationBatch batch) {
        if (batch.getId() == null) {
            ReconciliationBatchEntity entity = toEntity(batch);
            mapper.insert(entity);
            batch.setId(entity.getId());
            batch.setVersion(entity.getVersion());
            return batch;
        }
        ReconciliationBatchEntity entity = toEntity(batch);
        if (mapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT,
                    "reconciliation batch concurrent update: " + batch.getId());
        }
        batch.setVersion(batch.getVersion() + 1);
        return batch;
    }

    private ReconciliationBatch toDomain(ReconciliationBatchEntity entity) {
        return ReconciliationBatch.rehydrate(entity.getId(), entity.getVersion(), entity.getPeriod(),
                entity.getSource(), ReconciliationStatus.valueOf(entity.getStatus()),
                parseMatches(entity.getMatchesJson()), parseDifferences(entity.getDifferencesJson()),
                parseSource(entity.getStatementSource()), entity.getClosedBy(), entity.getClosedAt());
    }

    private ReconciliationBatchEntity toEntity(ReconciliationBatch batch) {
        ReconciliationBatchEntity entity = new ReconciliationBatchEntity();
        entity.setId(batch.getId());
        entity.setPeriod(batch.getPeriod());
        entity.setSource(batch.getSource());
        entity.setStatus(batch.getStatus().name());
        entity.setMatchesJson(serializeMatches(batch.getMatches()));
        entity.setDifferencesJson(serializeDifferences(batch.getDifferences()));
        entity.setStatementSource(serializeSource(batch.getStatementSource()));
        entity.setClosedBy(batch.getClosedBy());
        entity.setClosedAt(batch.getClosedAt());
        entity.setVersion(batch.getVersion());
        return entity;
    }

    private String serializeMatches(List<Match> matches) {
        try {
            return MAPPER.writeValueAsString(matches);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to serialize matches", e);
        }
    }

    private String serializeDifferences(List<Difference> differences) {
        try {
            return MAPPER.writeValueAsString(differences);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to serialize differences", e);
        }
    }

    private List<Match> parseMatches(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, MATCH_LIST);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to deserialize matches", e);
        }
    }

    private List<Difference> parseDifferences(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, DIFFERENCE_LIST);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to deserialize differences", e);
        }
    }

    private String serializeSource(ChannelStatementSource source) {
        if (source == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to serialize statement source", e);
        }
    }

    private ChannelStatementSource parseSource(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, SOURCE);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to deserialize statement source", e);
        }
    }
}
