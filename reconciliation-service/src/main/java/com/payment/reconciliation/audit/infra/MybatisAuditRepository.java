package com.payment.reconciliation.audit.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.audit.domain.AuditScope;
import com.payment.reconciliation.audit.domain.AuditBatchStatus;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审计仓储 MyBatis 实现（spec 017）：audit_batches / audit_differences / audit_adjustments 三表。
 * 批次更新走乐观锁（BaseEntity @Version），冲突抛 CONFLICT。
 */
@Repository
public class MybatisAuditRepository implements AuditRepository {

    private final AuditBatchMapper batchMapper;
    private final AuditDifferenceMapper differenceMapper;
    private final AuditAdjustmentMapper adjustmentMapper;

    public MybatisAuditRepository(AuditBatchMapper batchMapper,
                                  AuditDifferenceMapper differenceMapper,
                                  AuditAdjustmentMapper adjustmentMapper) {
        this.batchMapper = batchMapper;
        this.differenceMapper = differenceMapper;
        this.adjustmentMapper = adjustmentMapper;
    }

    @Override
    public Optional<AuditBatch> findBatchById(Long id) {
        AuditBatchEntity entity = batchMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toBatchDomain(entity));
    }

    @Override
    public Optional<AuditBatch> findBatchByNo(String batchNo) {
        AuditBatchEntity entity = batchMapper.selectOne(
                Wrappers.<AuditBatchEntity>lambdaQuery().eq(AuditBatchEntity::getBatchNo, batchNo));
        return entity == null ? Optional.empty() : Optional.of(toBatchDomain(entity));
    }

    @Override
    public Optional<AuditBatch> findBatchByPeriodAndScope(String period, AuditScope scope) {
        AuditBatchEntity entity = batchMapper.selectOne(
                Wrappers.<AuditBatchEntity>lambdaQuery()
                        .eq(AuditBatchEntity::getPeriod, period)
                        .eq(AuditBatchEntity::getScope, scope.name()));
        return entity == null ? Optional.empty() : Optional.of(toBatchDomain(entity));
    }

    @Override
    public AuditBatch insertBatch(AuditBatch batch) {
        AuditBatchEntity entity = toBatchEntity(batch);
        batchMapper.insert(entity);
        batch.setId(entity.getId());
        batch.setVersion(entity.getVersion());
        return batch;
    }

    @Override
    public AuditBatch saveBatch(AuditBatch batch) {
        AuditBatchEntity entity = toBatchEntity(batch);
        if (batch.getId() != null && mapperRowExists(batch)) {
            if (batchMapper.updateById(entity) == 0) {
                throw BizException.of(ErrorCodes.CONFLICT,
                        "audit batch concurrent update: " + batch.getId());
            }
            batch.setVersion(batch.getVersion() + 1);
            return batch;
        }
        return insertBatch(batch);
    }

    private boolean mapperRowExists(AuditBatch batch) {
        return batch.getId() != null && batchMapper.selectById(batch.getId()) != null;
    }

    @Override
    public AuditDifference saveDifference(Long batchId, AuditDifference difference) {
        difference.setBatchId(batchId);
        if (difference.getId() == null) {
            AuditDifferenceEntity entity = toDifferenceEntity(difference);
            differenceMapper.insert(entity);
            difference.setId(entity.getId());
            difference.setVersion(entity.getVersion());
            return difference;
        }
        AuditDifferenceEntity entity = toDifferenceEntity(difference);
        if (differenceMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT,
                    "audit difference concurrent update: " + difference.getId());
        }
        difference.setVersion((difference.getVersion() == null ? 1 : difference.getVersion()) + 1);
        return difference;
    }

    @Override
    public List<AuditDifference> findDifferencesByBatch(Long batchId) {
        return differenceMapper.selectList(
                        Wrappers.<AuditDifferenceEntity>lambdaQuery()
                                .eq(AuditDifferenceEntity::getBatchId, batchId)
                                .orderByAsc(AuditDifferenceEntity::getId))
                .stream().map(this::toDifferenceDomain).toList();
    }

    @Override
    public Optional<AuditDifference> findDifferenceById(Long id) {
        AuditDifferenceEntity entity = differenceMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDifferenceDomain(entity));
    }

    @Override
    public AuditAdjustment insertAdjustment(AuditAdjustment adjustment) {
        AuditAdjustmentEntity entity = toAdjustmentEntity(adjustment);
        adjustmentMapper.insert(entity);
        adjustment.setId(entity.getId());
        return adjustment;
    }

    @Override
    public Optional<AuditAdjustment> findAdjustmentByNo(String adjustNo) {
        AuditAdjustmentEntity entity = adjustmentMapper.selectOne(
                Wrappers.<AuditAdjustmentEntity>lambdaQuery().eq(AuditAdjustmentEntity::getAdjustNo, adjustNo));
        return entity == null ? Optional.empty() : Optional.of(toAdjustmentDomain(entity));
    }

    @Override
    public List<AuditAdjustment> findAdjustmentsByBatch(Long batchId) {
        return adjustmentMapper.selectList(
                        Wrappers.<AuditAdjustmentEntity>lambdaQuery()
                                .eq(AuditAdjustmentEntity::getBatchId, batchId)
                                .orderByAsc(AuditAdjustmentEntity::getId))
                .stream().map(this::toAdjustmentDomain).toList();
    }

    @Override
    public List<AuditAdjustment> findAdjustmentsByDifference(Long differenceId) {
        return adjustmentMapper.selectList(
                        Wrappers.<AuditAdjustmentEntity>lambdaQuery()
                                .eq(AuditAdjustmentEntity::getDifferenceId, differenceId))
                .stream().map(this::toAdjustmentDomain).toList();
    }

    @Override
    public long sumUnclosedSuspendedAmountMinor() {
        List<AuditDifference> all = differenceMapper.selectList(
                        Wrappers.<AuditDifferenceEntity>lambdaQuery()
                                .in(AuditDifferenceEntity::getStatus, "SUSPENDED"))
                .stream().map(this::toDifferenceDomain).toList();
        // 未收口差异的挂账净额 = 累计挂账 − 累计调账（SUSPENDED 剩余挂在 SUSPENSE 的部分）
        long total = 0;
        for (AuditDifference d : all) {
            total += Math.max(0, d.getSuspendedAmountMinor() - d.getAdjustedAmountMinor());
        }
        return total;
    }

    // ---- mapping ----

    private AuditBatch toBatchDomain(AuditBatchEntity entity) {
        List<AuditDifference> differences = findDifferencesByBatch(entity.getId());
        return AuditBatch.rehydrate(entity.getId(), entity.getBatchNo(), entity.getVersion(),
                entity.getPeriod(), AuditScope.valueOf(entity.getScope()),
                AuditBatchStatus.valueOf(entity.getStatus()),
                entity.getCheckedCount() == null ? 0 : entity.getCheckedCount(),
                entity.getSuspendedAmountMinor() == null ? 0 : entity.getSuspendedAmountMinor(),
                entity.getAdjustedAmountMinor() == null ? 0 : entity.getAdjustedAmountMinor(),
                entity.getTriggeredBy(), entity.getStartedAt(), entity.getFinishedAt(), differences);
    }

    private AuditBatchEntity toBatchEntity(AuditBatch batch) {
        AuditBatchEntity entity = new AuditBatchEntity();
        entity.setId(batch.getId());
        entity.setBatchNo(batch.getBatchNo());
        entity.setPeriod(batch.getPeriod());
        entity.setScope(batch.getScope().name());
        entity.setStatus(batch.getStatus().name());
        entity.setCheckedCount(batch.getCheckedCount());
        entity.setSuspendedAmountMinor(batch.getSuspendedAmountMinor());
        entity.setAdjustedAmountMinor(batch.getAdjustedAmountMinor());
        entity.setTriggeredBy(batch.getTriggeredBy());
        entity.setStartedAt(toMysqlDatetime(batch.getStartedAt()));
        entity.setFinishedAt(toMysqlDatetime(batch.getFinishedAt()));
        entity.setVersion(batch.getVersion());
        return entity;
    }

    private AuditDifference toDifferenceDomain(AuditDifferenceEntity entity) {
        AuditDifference difference = new AuditDifference(entity.getId(), entity.getBatchId(),
                com.payment.reconciliation.audit.domain.AuditDifferenceKind.valueOf(entity.getKind()),
                com.payment.reconciliation.audit.domain.AuditSeverity.valueOf(entity.getSeverity()),
                entity.getSourceType(), entity.getSourceId(), entity.getReference(),
                entity.getExpectedAmountMinor(), entity.getActualAmountMinor(), entity.getCurrency(),
                com.payment.reconciliation.audit.domain.AuditDifferenceStatus.valueOf(entity.getStatus()),
                entity.getSuspendedAmountMinor(), entity.getAdjustedAmountMinor(), entity.getDetail(),
                entity.getResolutionNote(), entity.getResolvedBy(),
                entity.getResolvedAt() == null ? null : entity.getResolvedAt().toString());
        difference.setVersion(entity.getVersion() == null ? 1 : entity.getVersion());
        difference.setTransferredOutMinor(entity.getTransferredOutMinor() == null ? 0L : entity.getTransferredOutMinor());
        return difference;
    }

    private AuditDifferenceEntity toDifferenceEntity(AuditDifference difference) {
        AuditDifferenceEntity entity = new AuditDifferenceEntity();
        entity.setId(difference.getId());
        entity.setBatchId(difference.getBatchId());
        entity.setKind(difference.getKind().name());
        entity.setSeverity(difference.getSeverity().name());
        entity.setSourceType(difference.getSourceType());
        entity.setSourceId(difference.getSourceId());
        entity.setReference(difference.getReference());
        entity.setExpectedAmountMinor(difference.getExpectedAmountMinor());
        entity.setActualAmountMinor(difference.getActualAmountMinor());
        entity.setCurrency(difference.getCurrency());
        entity.setStatus(difference.getStatus().name());
        entity.setSuspendedAmountMinor(difference.getSuspendedAmountMinor());
        entity.setAdjustedAmountMinor(difference.getAdjustedAmountMinor());
        entity.setDetail(difference.getDetail());
        entity.setResolutionNote(difference.getResolutionNote());
        entity.setResolvedBy(difference.getResolvedBy());
        entity.setResolvedAt(toMysqlDatetime(difference.getResolvedAt()));
        entity.setVersion(difference.getVersion() == null ? 1 : difference.getVersion());
        entity.setTransferredOutMinor(difference.getTransferredOutMinor());
        return entity;
    }

    private AuditAdjustment toAdjustmentDomain(AuditAdjustmentEntity entity) {
        return new AuditAdjustment(entity.getId(), entity.getAdjustNo(), entity.getBatchId(),
                entity.getDifferenceId(),
                com.payment.reconciliation.audit.domain.AuditAdjustmentKind.valueOf(entity.getKind()),
                entity.getDebitAccountCode(), entity.getCreditAccountCode(),
                entity.getAmountMinor() == null ? 0 : entity.getAmountMinor(), entity.getCurrency(),
                entity.getPostingNo(), entity.getStatus(), entity.getOperator(), entity.getReviewer(),
                entity.getReason());
    }

    private AuditAdjustmentEntity toAdjustmentEntity(AuditAdjustment adjustment) {
        AuditAdjustmentEntity entity = new AuditAdjustmentEntity();
        entity.setId(adjustment.getId());
        entity.setAdjustNo(adjustment.getAdjustNo());
        entity.setBatchId(adjustment.getBatchId());
        entity.setDifferenceId(adjustment.getDifferenceId());
        entity.setKind(adjustment.getKind().name());
        entity.setDebitAccountCode(adjustment.getDebitAccountCode());
        entity.setCreditAccountCode(adjustment.getCreditAccountCode());
        entity.setAmountMinor(adjustment.getAmountMinor());
        entity.setCurrency(adjustment.getCurrency());
        entity.setPostingNo(adjustment.getPostingNo());
        entity.setStatus(adjustment.getStatus());
        entity.setOperator(adjustment.getOperator());
        entity.setReviewer(adjustment.getReviewer());
        entity.setReason(adjustment.getReason());
        return entity;
    }

    /** ISO-8601 → MySQL DATETIME 兼容格式（与 MybatisReconciliationRepository 同口径）。 */
    private static String toMysqlDatetime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime().toString().replace('T', ' ');
        } catch (Exception e) {
            return iso.replace('T', ' ');
        }
    }

    private static java.time.LocalDateTime toLocalDateTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime();
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(iso.replace('T', ' '));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
