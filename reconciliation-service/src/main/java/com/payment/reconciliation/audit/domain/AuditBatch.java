package com.payment.reconciliation.audit.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 审计批次聚合根（spec 017 / FR-003）：一次 (period, scope) 核对作业的载体。
 *
 * <p>状态机见 {@link AuditBatchStatus}；关批门禁（FR-018）：存在未收口差异时 MUST 拒绝。
 * 幂等以 (period, scope) 唯一约束兜底，重复触发回查首批次。</p>
 */
public class AuditBatch {

    private Long id;
    private final String batchNo;
    private Integer version;
    private final String period;
    private final AuditScope scope;
    private AuditBatchStatus status;
    private int checkedCount;
    private long suspendedAmountMinor;
    private long adjustedAmountMinor;
    private final String triggeredBy;
    private String startedAt;
    private String finishedAt;
    private final List<AuditDifference> differences = new ArrayList<>();

    public AuditBatch(String period, AuditScope scope, String triggeredBy) {
        this(null, BusinessNos.of(BusinessNoType.AUDIT_BATCH), null, period, scope, triggeredBy);
    }

    private AuditBatch(Long id, String batchNo, Integer version, String period, AuditScope scope, String triggeredBy) {
        this.id = id;
        this.batchNo = batchNo;
        this.version = version;
        this.period = Objects.requireNonNull(period, "period");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.status = AuditBatchStatus.PROCESSING;
        this.triggeredBy = triggeredBy == null || triggeredBy.isBlank() ? "system" : triggeredBy;
    }

    /** 持久化重建（MyBatis 还原入口）：单号以落库值为准。 */
    public static AuditBatch rehydrate(Long id, String batchNo, Integer version, String period, AuditScope scope,
                                       AuditBatchStatus status, int checkedCount, long suspendedAmountMinor,
                                       long adjustedAmountMinor, String triggeredBy, String startedAt,
                                       String finishedAt, List<AuditDifference> differences) {
        AuditBatch batch = new AuditBatch(id, batchNo, version, period, scope, triggeredBy);
        batch.status = status;
        batch.checkedCount = checkedCount;
        batch.suspendedAmountMinor = suspendedAmountMinor;
        batch.adjustedAmountMinor = adjustedAmountMinor;
        batch.startedAt = startedAt;
        batch.finishedAt = finishedAt;
        if (differences != null) {
            batch.differences.addAll(differences);
        }
        return batch;
    }

    /** 核对完成落定：BALANCED 或 HAS_DIFFERENCE。 */
    public void finish(int checkedCount, List<AuditDifference> differences) {
        requireStatus(AuditBatchStatus.PROCESSING, AuditBatchStatus.RECHECKING);
        this.checkedCount = checkedCount;
        this.differences.clear();
        if (differences != null) {
            this.differences.addAll(differences);
        }
        this.status = this.differences.isEmpty() ? AuditBatchStatus.BALANCED : AuditBatchStatus.HAS_DIFFERENCE;
        this.finishedAt = Instant.now().toString();
    }

    /** 进入复核中（recheck 触发时）。 */
    public void beginRechecking() {
        if (status != AuditBatchStatus.HAS_DIFFERENCE && status != AuditBatchStatus.BALANCED
                && status != AuditBatchStatus.RECHECKING) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "audit batch cannot recheck from " + status);
        }
        this.status = AuditBatchStatus.RECHECKING;
    }

    /**
     * 关批（FR-018 门禁）：存在 PENDING / SUSPENDED / ADJUSTED 差异时拒绝（400）；
     * 全部 VERIFIED / RESOLVED 才允许 CLOSED。
     */
    public void close(String operator) {
        requireStatus(AuditBatchStatus.BALANCED, AuditBatchStatus.HAS_DIFFERENCE, AuditBatchStatus.RECHECKING);
        List<AuditDifference> unclosed = differences.stream()
                .filter(d -> d.getStatus().unclosed())
                .toList();
        if (!unclosed.isEmpty()) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "unclosed differences present: " + unclosed.size()
                            + " (e.g. " + unclosed.get(0).getKind() + "/" + unclosed.get(0).getSourceId() + ")");
        }
        this.status = AuditBatchStatus.CLOSED;
        this.finishedAt = Instant.now().toString();
    }

    public boolean hasUnclosedDifferences() {
        return differences.stream().anyMatch(d -> d.getStatus().unclosed());
    }

    public List<AuditDifference> unclosedDifferences() {
        return differences.stream().filter(d -> d.getStatus().unclosed()).toList();
    }

    private void requireStatus(AuditBatchStatus... allowed) {
        for (AuditBatchStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                "audit batch illegal status transition from " + status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getPeriod() {
        return period;
    }

    public AuditScope getScope() {
        return scope;
    }

    public AuditBatchStatus getStatus() {
        return status;
    }

    public void setStatus(AuditBatchStatus status) {
        this.status = status;
    }

    public int getCheckedCount() {
        return checkedCount;
    }

    public void setCheckedCount(int checkedCount) {
        this.checkedCount = checkedCount;
    }

    public long getSuspendedAmountMinor() {
        return suspendedAmountMinor;
    }

    public void setSuspendedAmountMinor(long suspendedAmountMinor) {
        this.suspendedAmountMinor = suspendedAmountMinor;
    }

    public long getAdjustedAmountMinor() {
        return adjustedAmountMinor;
    }

    public void setAdjustedAmountMinor(long adjustedAmountMinor) {
        this.adjustedAmountMinor = adjustedAmountMinor;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public List<AuditDifference> getDifferences() {
        return differences;
    }
}
