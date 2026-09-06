package com.payment.reconciliation.audit.domain;

import java.util.List;
import java.util.Optional;

/**
 * 审计仓储边界（领域接口）：audit_batches / audit_differences / audit_adjustments 三表。
 * 生产 MyBatis 实现，测试内存实现。
 */
public interface AuditRepository {

    Optional<AuditBatch> findBatchById(Long id);

    Optional<AuditBatch> findBatchByNo(String batchNo);

    Optional<AuditBatch> findBatchByPeriodAndScope(String period, AuditScope scope);

    AuditBatch insertBatch(AuditBatch batch);

    AuditBatch saveBatch(AuditBatch batch);

    /** 保存差异并回填自增 id（插入路径）；已有 id 的差异走更新。 */
    AuditDifference saveDifference(Long batchId, AuditDifference difference);

    List<AuditDifference> findDifferencesByBatch(Long batchId);

    Optional<AuditDifference> findDifferenceById(Long id);

    AuditAdjustment insertAdjustment(AuditAdjustment adjustment);

    Optional<AuditAdjustment> findAdjustmentByNo(String adjustNo);

    List<AuditAdjustment> findAdjustmentsByBatch(Long batchId);

    List<AuditAdjustment> findAdjustmentsByDifference(Long differenceId);

    /** 未收口挂账净额合计（SC-016：SUSPENSE 勾稽）。 */
    long sumUnclosedSuspendedAmountMinor();
}
