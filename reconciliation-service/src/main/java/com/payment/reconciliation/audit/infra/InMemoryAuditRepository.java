package com.payment.reconciliation.audit.infra;

import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.audit.domain.AuditScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存审计仓储：仅用于领域 / 应用单测（不走 Spring 注入），生产由 {@link MybatisAuditRepository} 承接。
 */
public class InMemoryAuditRepository implements AuditRepository {

    private final Map<String, AuditBatch> batchesByNo = new LinkedHashMap<>();
    private final Map<Long, AuditDifference> differences = new LinkedHashMap<>();
    private final Map<String, AuditAdjustment> adjustments = new LinkedHashMap<>();
    private final AtomicLong batchIdGen = new AtomicLong();
    private final AtomicLong differenceIdGen = new AtomicLong();
    private final AtomicLong adjustmentIdGen = new AtomicLong();

    @Override
    public Optional<AuditBatch> findBatchById(Long id) {
        return batchesByNo.values().stream().filter(b -> id.equals(b.getId())).findFirst();
    }

    @Override
    public Optional<AuditBatch> findBatchByNo(String batchNo) {
        return Optional.ofNullable(batchesByNo.get(batchNo));
    }

    @Override
    public Optional<AuditBatch> findBatchByPeriodAndScope(String period, AuditScope scope) {
        return batchesByNo.values().stream()
                .filter(b -> b.getPeriod().equals(period) && b.getScope() == scope)
                .findFirst();
    }

    @Override
    public AuditBatch insertBatch(AuditBatch batch) {
        boolean duplicate = findBatchByPeriodAndScope(batch.getPeriod(), batch.getScope()).isPresent();
        if (duplicate) {
            throw new org.springframework.dao.DuplicateKeyException(
                    "uk_audit_batches_period_scope: " + batch.getPeriod() + "/" + batch.getScope());
        }
        batch.setId(batchIdGen.incrementAndGet());
        batch.setVersion(1);
        batchesByNo.put(batch.getBatchNo(), batch);
        return batch;
    }

    @Override
    public AuditBatch saveBatch(AuditBatch batch) {
        batchesByNo.put(batch.getBatchNo(), batch);
        return batch;
    }

    @Override
    public AuditDifference saveDifference(Long batchId, AuditDifference difference) {
        difference.setBatchId(batchId);
        if (difference.getId() == null) {
            difference.setId(differenceIdGen.incrementAndGet());
        }
        differences.put(difference.getId(), difference);
        return difference;
    }

    @Override
    public List<AuditDifference> findDifferencesByBatch(Long batchId) {
        return differences.values().stream()
                .filter(d -> batchId.equals(d.getBatchId()))
                .sorted(java.util.Comparator.comparingLong(AuditDifference::getId))
                .toList();
    }

    @Override
    public Optional<AuditDifference> findDifferenceById(Long id) {
        return Optional.ofNullable(differences.get(id));
    }

    @Override
    public AuditAdjustment insertAdjustment(AuditAdjustment adjustment) {
        if (adjustments.containsKey(adjustment.getAdjustNo())) {
            throw new org.springframework.dao.DuplicateKeyException(
                    "uk_adjustments_adjust_no: " + adjustment.getAdjustNo());
        }
        adjustment.setId(adjustmentIdGen.incrementAndGet());
        adjustments.put(adjustment.getAdjustNo(), adjustment);
        return adjustment;
    }

    @Override
    public Optional<AuditAdjustment> findAdjustmentByNo(String adjustNo) {
        return Optional.ofNullable(adjustments.get(adjustNo));
    }

    @Override
    public List<AuditAdjustment> findAdjustmentsByBatch(Long batchId) {
        return adjustments.values().stream()
                .filter(a -> batchId.equals(a.getBatchId()))
                .toList();
    }

    @Override
    public List<AuditAdjustment> findAdjustmentsByDifference(Long differenceId) {
        return adjustments.values().stream()
                .filter(a -> differenceId.equals(a.getDifferenceId()))
                .toList();
    }

    @Override
    public long sumUnclosedSuspendedAmountMinor() {
        return new ArrayList<>(differences.values()).stream()
                .filter(d -> d.getStatus() == com.payment.reconciliation.audit.domain.AuditDifferenceStatus.SUSPENDED)
                .mapToLong(d -> Math.max(0, d.getSuspendedAmountMinor() - d.getAdjustedAmountMinor()))
                .sum();
    }
}
