package com.payment.reconciliation.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.DifferenceStatus;
import com.payment.reconciliation.domain.DifferenceType;
import com.payment.reconciliation.domain.Match;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationDifference;
import com.payment.reconciliation.domain.ReconciliationRepository;
import com.payment.reconciliation.domain.ReconciliationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 对账批次仓储 MyBatis 实现：批次落自有 Schema，匹配以 JSON 内嵌随聚合读写；
 * 差异自 032 起拆表（reconciliation_differences 权威台账）。
 *
 * <p>批次更新走乐观锁：先读当前版本，再 {@code updateById}，冲突（0 行命中）抛 {@link ErrorCodes#CONFLICT}，
 * 杜绝并发直改状态覆盖。differences_json <b>停写不停读</b>（spec 032 §9）：新批次（importId 非空）
 * 不再写 JSON，聚合加载时由差异台账水合；legacy 批次 JSON 仍按原样读。</p>
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
    private final ReconciliationDifferenceMapper differenceMapper;

    public MybatisReconciliationRepository(ReconciliationBatchMapper mapper,
                                           ReconciliationDifferenceMapper differenceMapper) {
        this.mapper = mapper;
        this.differenceMapper = differenceMapper;
    }

    @Override
    public Optional<ReconciliationBatch> findById(Long id) {
        ReconciliationBatchEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<ReconciliationBatch> findByPeriod(String period) {
        // 032「最新批」语义：同周期可并存多份导入批次，读路径取 id 最大者。
        List<ReconciliationBatchEntity> rows = mapper.selectList(
                Wrappers.<ReconciliationBatchEntity>lambdaQuery()
                        .eq(ReconciliationBatchEntity::getPeriod, period)
                        .orderByDesc(ReconciliationBatchEntity::getId)
                        .last("LIMIT 1"));
        return rows.stream().findFirst().map(this::toDomain);
    }

    @Override
    public Optional<ReconciliationBatch> findByPeriodAndImport(String period, String channelCode, Long importId) {
        ReconciliationBatchEntity entity = mapper.selectOne(
                Wrappers.<ReconciliationBatchEntity>lambdaQuery()
                        .eq(ReconciliationBatchEntity::getPeriod, period)
                        .eq(ReconciliationBatchEntity::getChannelCode, channelCode)
                        .eq(importId != null, ReconciliationBatchEntity::getImportId, importId)
                        .isNull(importId == null, ReconciliationBatchEntity::getImportId));
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

    // ---- 差异台账 ----

    @Override
    @Transactional
    public ReconciliationDifference insertDifference(ReconciliationDifference difference) {
        ReconciliationDifferenceEntity entity = toEntity(difference);
        try {
            differenceMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // uk_diff_identity：同周期同类差异重复产生 ⇒ 幂等吸收，返回既有台账行（spec §8.3 第三层）。
            return findDifferenceByIdentity(difference)
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "difference identity conflict: " + difference.getDiffNo()));
        }
        difference.setId(entity.getId());
        return difference;
    }

    @Override
    public Optional<ReconciliationDifference> findDifferenceByNo(String diffNo) {
        ReconciliationDifferenceEntity entity = differenceMapper.selectOne(
                Wrappers.<ReconciliationDifferenceEntity>lambdaQuery()
                        .eq(ReconciliationDifferenceEntity::getDiffNo, diffNo));
        return entity == null ? Optional.empty() : Optional.of(toDifferenceDomain(entity));
    }

    @Override
    @Transactional
    public ReconciliationDifference updateDifference(ReconciliationDifference difference) {
        ReconciliationDifferenceEntity entity = toEntity(difference);
        differenceMapper.updateById(entity);
        return difference;
    }

    @Override
    public List<ReconciliationDifference> findDifferencesByBatch(Long batchId) {
        return differenceMapper.selectList(Wrappers.<ReconciliationDifferenceEntity>lambdaQuery()
                        .eq(ReconciliationDifferenceEntity::getBatchId, batchId)
                        .orderByAsc(ReconciliationDifferenceEntity::getId))
                .stream()
                .map(this::toDifferenceDomain)
                .toList();
    }

    @Override
    public DifferencePage findDifferences(String period, String status, String merchantId, String kind,
                                          int page, int size) {
        Page<ReconciliationDifferenceEntity> result = differenceMapper.selectPage(
                new Page<>(page + 1, size),
                Wrappers.<ReconciliationDifferenceEntity>lambdaQuery()
                        .eq(period != null, ReconciliationDifferenceEntity::getPeriod, period)
                        .eq(status != null, ReconciliationDifferenceEntity::getStatus, status)
                        .eq(merchantId != null, ReconciliationDifferenceEntity::getMerchantId, merchantId)
                        .eq(kind != null, ReconciliationDifferenceEntity::getKind, kind)
                        .orderByDesc(ReconciliationDifferenceEntity::getId));
        List<ReconciliationDifference> items = result.getRecords().stream()
                .map(this::toDifferenceDomain)
                .toList();
        return new DifferencePage(items, result.getTotal());
    }

    private Optional<ReconciliationDifference> findDifferenceByIdentity(ReconciliationDifference difference) {
        ReconciliationDifferenceEntity entity = differenceMapper.selectOne(
                Wrappers.<ReconciliationDifferenceEntity>lambdaQuery()
                        .eq(ReconciliationDifferenceEntity::getPeriod, difference.getPeriod())
                        .eq(ReconciliationDifferenceEntity::getKind, difference.getKind().name())
                        .eq(difference.getReferenceType() != null,
                                ReconciliationDifferenceEntity::getReferenceType, difference.getReferenceType())
                        .isNull(difference.getReferenceType() == null,
                                ReconciliationDifferenceEntity::getReferenceType)
                        .eq(ReconciliationDifferenceEntity::getReference, difference.getReference()));
        return entity == null ? Optional.empty() : Optional.of(toDifferenceDomain(entity));
    }

    // ---- 批次 ⇄ PO 映射（含差异台账水合）----

    private ReconciliationBatch toDomain(ReconciliationBatchEntity entity) {
        List<Match> matches = parseMatches(entity.getMatchesJson());
        List<Difference> differences = loadDifferences(entity);
        return ReconciliationBatch.rehydrate(entity.getId(), entity.getBatchNo(), entity.getVersion(),
                entity.getPeriod(), entity.getSource(), entity.getChannelCode(), entity.getImportId(),
                ReconciliationStatus.valueOf(entity.getStatus()), matches, differences,
                parseSource(entity.getStatementSource()), entity.getClosedBy(), entity.getClosedAt());
    }

    /**
     * 差异装载：新批次（differences_json 空）从台账水合；legacy 批次读 JSON（停写不停读）。
     */
    private List<Difference> loadDifferences(ReconciliationBatchEntity entity) {
        if (entity.getDifferencesJson() != null && !entity.getDifferencesJson().isBlank()) {
            return parseDifferences(entity.getDifferencesJson());
        }
        return differenceMapper.selectList(Wrappers.<ReconciliationDifferenceEntity>lambdaQuery()
                        .eq(ReconciliationDifferenceEntity::getBatchId, entity.getId())
                        .orderByAsc(ReconciliationDifferenceEntity::getId))
                .stream()
                .map(MybatisReconciliationRepository::toAggregateView)
                .toList();
    }

    /** 台账行 → 批次内 Difference 视图（resolutionStatus 承载台账状态，供关批门禁判定）。 */
    private static Difference toAggregateView(ReconciliationDifferenceEntity entity) {
        return new Difference(entity.getReference(), DifferenceType.valueOf(entity.getKind()),
                entity.getExpectedAmountMinor(), entity.getActualAmountMinor(), null, null,
                entity.getStatus(), entity.getResolutionNote(), entity.getResolvedBy(), entity.getResolvedAt(),
                entity.getDiffNo(), entity.getMerchantId(),
                entity.getReferenceType(), entity.getFeeAmountMinor());
    }

    private ReconciliationBatchEntity toEntity(ReconciliationBatch batch) {
        ReconciliationBatchEntity entity = new ReconciliationBatchEntity();
        entity.setId(batch.getId());
        entity.setBatchNo(batch.getBatchNo());
        entity.setPeriod(batch.getPeriod());
        entity.setSource(batch.getSource());
        entity.setChannelCode(batch.getChannelCode());
        entity.setImportId(batch.getImportId());
        entity.setStatus(batch.getStatus().name());
        entity.setMatchesJson(serializeMatches(batch.getMatches()));
        // differences_json 停写：新批次差异只写 reconciliation_differences；legacy（无导入）批次维持 JSON 快照。
        entity.setDifferencesJson(batch.getImportId() == null ? serializeDifferences(batch.getDifferences()) : null);
        entity.setStatementSource(serializeSource(batch.getStatementSource()));
        entity.setClosedBy(batch.getClosedBy());
        entity.setClosedAt(toMysqlDatetime(batch.getClosedAt()));
        entity.setVersion(batch.getVersion());
        return entity;
    }

    private ReconciliationDifferenceEntity toEntity(ReconciliationDifference difference) {
        ReconciliationDifferenceEntity entity = new ReconciliationDifferenceEntity();
        entity.setId(difference.getId());
        entity.setDiffNo(difference.getDiffNo());
        entity.setBatchId(difference.getBatchId());
        entity.setImportId(difference.getImportId());
        entity.setPeriod(difference.getPeriod());
        entity.setMerchantId(difference.getMerchantId());
        entity.setChannelCode(difference.getChannelCode());
        entity.setKind(difference.getKind().name());
        entity.setSeverity(difference.getKind().severity());
        entity.setReferenceType(difference.getReferenceType());
        entity.setReference(difference.getReference());
        entity.setExpectedAmountMinor(difference.getExpectedAmountMinor());
        entity.setActualAmountMinor(difference.getActualAmountMinor());
        entity.setFeeAmountMinor(difference.getFeeAmountMinor());
        entity.setCurrency(difference.getCurrency());
        entity.setStatus(difference.getStatus().name());
        entity.setDispositionRef(difference.getDispositionRef());
        entity.setResolutionNote(difference.getResolutionNote());
        entity.setResolvedBy(difference.getResolvedBy());
        entity.setResolvedAt(toMysqlDatetime(difference.getResolvedAt()));
        entity.setVersion(1);
        return entity;
    }

    private ReconciliationDifference toDifferenceDomain(ReconciliationDifferenceEntity entity) {
        return ReconciliationDifference.rehydrate(entity.getId(), entity.getDiffNo(), entity.getBatchId(),
                entity.getImportId(), entity.getPeriod(), entity.getMerchantId(), entity.getChannelCode(),
                DifferenceType.valueOf(entity.getKind()), entity.getReferenceType(), entity.getReference(),
                entity.getExpectedAmountMinor(), entity.getActualAmountMinor(), entity.getFeeAmountMinor(),
                entity.getCurrency(),
                entity.getStatus() == null ? DifferenceStatus.PENDING : DifferenceStatus.valueOf(entity.getStatus()),
                entity.getDispositionRef(), entity.getResolutionNote(), entity.getResolvedBy(),
                entity.getResolvedAt());
    }

    /**
     * ISO-8601（{@code Instant.toString()}，含 'T'/'Z'）→ MySQL DATETIME 兼容格式（UTC）。
     * {@code closed_at} 列为 DATETIME，驱动不接受 ISO 字符串直写（MySQL 8 报
     * "Incorrect datetime value"）。领域/API 层保持 ISO 展示，仅在持久化边界转换。
     */
    private static String toMysqlDatetime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            return iso; // 非 ISO 值原样透传，交由数据库校验
        }
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
