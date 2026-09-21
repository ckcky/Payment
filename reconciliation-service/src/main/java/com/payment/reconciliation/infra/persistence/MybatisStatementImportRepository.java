package com.payment.reconciliation.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import com.payment.reconciliation.statement.StatementImportStatus;
import com.payment.reconciliation.statement.StatementLine;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账单导入仓储 MyBatis 实现：批次 + 标准化行落自有 Schema。
 *
 * <p>导入幂等（spec 032 §8.3 第一层）由唯一约束 {@code uk_import_identity} 承接：
 * 重放插入撞键时捕获 {@link DuplicateKeyException} 回查返回首次导入。</p>
 */
@Repository
public class MybatisStatementImportRepository implements StatementImportRepository {

    private final StatementImportMapper importMapper;
    private final StatementLineMapper lineMapper;

    public MybatisStatementImportRepository(StatementImportMapper importMapper, StatementLineMapper lineMapper) {
        this.importMapper = importMapper;
        this.lineMapper = lineMapper;
    }

    @Override
    @Transactional
    public StatementImport save(StatementImport imprt) {
        StatementImportEntity entity = toEntity(imprt);
        if (imprt.getId() == null) {
            try {
                importMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                // uk_import_no（雪花重复，理论不可能）或 uk_import_identity 并发重放：
                // 统一回查返回首次导入，幂等语义一致。
                throw e;
            }
            imprt.setId(entity.getId());
            imprt.setVersion(entity.getVersion());
            return imprt;
        }
        if (importMapper.updateById(entity) == 0) {
            return imprt; // 状态机不可逆（NORMALIZED/REJECTED 终态），并发重复更新视为成功空操作
        }
        imprt.setVersion(imprt.getVersion() == null ? 1 : imprt.getVersion() + 1);
        return imprt;
    }

    @Override
    @Transactional
    public void saveLines(Long importId, String channelCode, List<StatementLine> lines) {
        LocalDateTime now = LocalDateTime.now();
        for (StatementLine line : lines) {
            StatementLineEntity entity = toEntity(importId, line, now);
            try {
                lineMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                // uk(import_id, line_no)：同批次行号唯一。重放保护（正常路径下重放不会走到这）。
                throw new IllegalStateException("duplicate statement line: import " + importId
                        + " line " + line.lineNo(), e);
            }
        }
    }

    @Override
    public Optional<StatementImport> findById(Long id) {
        StatementImportEntity entity = importMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<StatementImport> findByNo(String importNo) {
        StatementImportEntity entity = importMapper.selectOne(
                Wrappers.<StatementImportEntity>lambdaQuery()
                        .eq(StatementImportEntity::getImportNo, importNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<StatementImport> findByIdentity(String channelCode, String period, String contentFingerprint) {
        StatementImportEntity entity = importMapper.selectOne(
                Wrappers.<StatementImportEntity>lambdaQuery()
                        .eq(StatementImportEntity::getChannelCode, channelCode)
                        .eq(StatementImportEntity::getPeriod, period)
                        .eq(StatementImportEntity::getContentFingerprint, contentFingerprint));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<StatementImport> findLatestNormalized(String period, String channelCode) {
        List<StatementImportEntity> rows = importMapper.selectList(
                Wrappers.<StatementImportEntity>lambdaQuery()
                        .eq(StatementImportEntity::getPeriod, period)
                        .eq(channelCode != null, StatementImportEntity::getChannelCode, channelCode)
                        .eq(StatementImportEntity::getStatus, StatementImportStatus.NORMALIZED.name())
                        .orderByDesc(StatementImportEntity::getId)
                        .last("LIMIT 1"));
        return rows.stream().findFirst().map(this::toDomain);
    }

    @Override
    public List<StatementImport> list(String period, String channelCode) {
        return importMapper.selectList(Wrappers.<StatementImportEntity>lambdaQuery()
                        .eq(period != null, StatementImportEntity::getPeriod, period)
                        .eq(channelCode != null, StatementImportEntity::getChannelCode, channelCode)
                        .orderByDesc(StatementImportEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<StatementLine> findLines(Long importId) {
        return lineMapper.selectList(Wrappers.<StatementLineEntity>lambdaQuery()
                        .eq(StatementLineEntity::getImportId, importId)
                        .orderByAsc(StatementLineEntity::getLineNo))
                .stream()
                .map(MybatisStatementImportRepository::toDomain)
                .toList();
    }

    private StatementImport toDomain(StatementImportEntity entity) {
        return StatementImport.rehydrate(entity.getId(), entity.getImportNo(), entity.getVersion(),
                entity.getChannelCode(), entity.getPeriod(), entity.getSourceType(),
                entity.getContentFingerprint(), entity.getRowCount() == null ? 0 : entity.getRowCount(),
                StatementImportStatus.valueOf(entity.getStatus()), entity.getErrorReason(),
                entity.getImportedBy(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
    }

    private StatementImportEntity toEntity(StatementImport imprt) {
        StatementImportEntity entity = new StatementImportEntity();
        entity.setId(imprt.getId());
        entity.setImportNo(imprt.getImportNo());
        entity.setChannelCode(imprt.getChannelCode());
        entity.setPeriod(imprt.getPeriod());
        entity.setSourceType(imprt.getSourceType());
        entity.setContentFingerprint(imprt.getContentFingerprint());
        entity.setRowCount(imprt.getRowCount());
        entity.setStatus(imprt.getStatus().name());
        entity.setErrorReason(imprt.getErrorReason());
        entity.setImportedBy(imprt.getImportedBy());
        entity.setVersion(imprt.getVersion());
        return entity;
    }

    private static StatementLineEntity toEntity(Long importId, StatementLine line, LocalDateTime now) {
        StatementLineEntity entity = new StatementLineEntity();
        entity.setImportId(importId);
        entity.setLineNo(line.lineNo());
        entity.setChannelCode(line.channelCode());
        entity.setChannelTxnNo(line.channelTxnNo());
        entity.setReferenceType(line.referenceType());
        entity.setReference(line.reference());
        entity.setReferenceKind(line.referenceKind());
        entity.setMerchantId(line.merchantId());
        entity.setAmountMinor(line.amountMinor());
        entity.setFeeMinor(line.feeMinor());
        entity.setCurrency(line.currency());
        entity.setStatus(line.status());
        entity.setOccurredAt(line.occurredAt());
        entity.setRawText(line.rawText());
        entity.setCreatedAt(now);
        return entity;
    }

    private static StatementLine toDomain(StatementLineEntity entity) {
        return new StatementLine(entity.getImportId(), entity.getLineNo(), entity.getChannelCode(),
                entity.getChannelTxnNo(), entity.getReferenceType(), entity.getReference(),
                entity.getReferenceKind(), entity.getMerchantId(),
                entity.getAmountMinor() == null ? 0L : entity.getAmountMinor(),
                entity.getFeeMinor() == null ? 0L : entity.getFeeMinor(),
                entity.getCurrency(), entity.getStatus(), entity.getOccurredAt(), entity.getRawText());
    }
}
