package com.payment.reconciliation.audit.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.Objects;

/**
 * 审计差异（spec 017）：一次核对产出的单条不一致事实，承载处置状态机。
 *
 * <p>差异是「只读比对」的产物：比对过程不修正任何业务数据（FR-002）；
 * 处置（挂账 / 调账）只更新本对象状态与累计金额，资金动作由 ledger 的 ADJUSTMENT 分录承载。</p>
 */
public final class AuditDifference {

    private Long id;
    private Long batchId;
    private final AuditDifferenceKind kind;
    private final AuditSeverity severity;
    private final String sourceType;
    private final String sourceId;
    private final String reference;
    private final Long expectedAmountMinor;
    private final Long actualAmountMinor;
    private final String currency;
    private AuditDifferenceStatus status;
    private long suspendedAmountMinor;
    private long adjustedAmountMinor;
    private long transferredOutMinor;
    private String detail;
    private String resolutionNote;
    private String resolvedBy;
    private String resolvedAt;
    private Integer version;

    @JsonCreator
    public AuditDifference(
            @JsonProperty("id") Long id,
            @JsonProperty("batchId") Long batchId,
            @JsonProperty("kind") AuditDifferenceKind kind,
            @JsonProperty("severity") AuditSeverity severity,
            @JsonProperty("sourceType") String sourceType,
            @JsonProperty("sourceId") String sourceId,
            @JsonProperty("reference") String reference,
            @JsonProperty("expectedAmountMinor") Long expectedAmountMinor,
            @JsonProperty("actualAmountMinor") Long actualAmountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("status") AuditDifferenceStatus status,
            @JsonProperty("suspendedAmountMinor") Long suspendedAmountMinor,
            @JsonProperty("adjustedAmountMinor") Long adjustedAmountMinor,
            @JsonProperty("detail") String detail,
            @JsonProperty("resolutionNote") String resolutionNote,
            @JsonProperty("resolvedBy") String resolvedBy,
            @JsonProperty("resolvedAt") String resolvedAt) {
        this.id = id;
        this.batchId = batchId;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.severity = severity == null ? kind.defaultSeverity() : severity;
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.reference = reference;
        this.expectedAmountMinor = expectedAmountMinor;
        this.actualAmountMinor = actualAmountMinor;
        this.currency = currency == null ? "CNY" : currency;
        this.status = status == null ? AuditDifferenceStatus.PENDING : status;
        this.suspendedAmountMinor = suspendedAmountMinor == null ? 0L : suspendedAmountMinor;
        this.adjustedAmountMinor = adjustedAmountMinor == null ? 0L : adjustedAmountMinor;
        this.detail = detail;
        this.resolutionNote = resolutionNote;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
    }

    /** 新建差异工厂：初始 PENDING。 */
    public static AuditDifference of(AuditDifferenceKind kind, String sourceType, String sourceId,
                                     String reference, Long expected, Long actual, String currency, String detail) {
        return new AuditDifference(null, null, kind, kind.defaultSeverity(), sourceType, sourceId,
                reference, expected, actual, currency, AuditDifferenceStatus.PENDING, 0L, 0L,
                detail, null, null, null);
    }

    /** 差异金额（绝对值）：挂账 / 调账的限额口径。 */
    public long differenceAmountMinor() {
        if (expectedAmountMinor == null || actualAmountMinor == null) {
            return 0L;
        }
        return Math.abs(expectedAmountMinor - actualAmountMinor);
    }

    /** 剩余可处置金额：差异金额 − 已挂账 − 已调账（FR-016 ④ 累计不超差异额）。 */
    public long remainingAmountMinor() {
        return Math.max(0L, differenceAmountMinor() - suspendedAmountMinor - adjustedAmountMinor);
    }

    /** 挂账（FR-014）：仅 PENDING / ADJUSTED 可挂账；amount MUST > 0 且不超剩余额度。 */
    public void suspend(long amountMinor) {
        requireDisposable();
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "suspend amount must be > 0");
        }
        if (amountMinor > remainingAmountMinor()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "ADJUST_AMOUNT_EXCEEDED");
        }
        this.status = AuditDifferenceStatus.SUSPENDED;
        this.suspendedAmountMinor += amountMinor;
    }

    /** 调账（FR-015）：登记调账金额；校验与挂账同口径。 */
    public void applyAdjustment(long amountMinor) {
        requireDisposable();
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "adjust amount must be > 0");
        }
        if (amountMinor > remainingAmountMinor()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "ADJUST_AMOUNT_EXCEEDED");
        }
        this.status = AuditDifferenceStatus.ADJUSTED;
        this.adjustedAmountMinor += amountMinor;
    }

    /**
     * 转出（TRANSFER）：处置的是「已挂在 SUSPENSE 的金额」而非新增额度，
     * 因此从挂账余额中划转（MUST ≤ 已挂账未转出部分），并计入累计调账。
     */
    public void transferOut(long amountMinor) {
        requireDisposable();
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "transfer amount must be > 0");
        }
        if (amountMinor > this.suspendedAmountMinor - this.transferredOutMinor) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "ADJUST_AMOUNT_EXCEEDED");
        }
        this.transferredOutMinor += amountMinor;
        this.status = AuditDifferenceStatus.ADJUSTED;
        this.adjustedAmountMinor += amountMinor;
    }

    /** recheck 通过（FR-017）：差异置 VERIFIED。 */
    public void verify() {
        this.status = AuditDifferenceStatus.VERIFIED;
    }

    /** recheck 未通过：退回 SUSPENDED 继续暴露（保留累计处置金额）。 */
    public void rejectRecheck() {
        if (this.status == AuditDifferenceStatus.VERIFIED) {
            this.status = AuditDifferenceStatus.SUSPENDED;
        }
    }

    private void requireDisposable() {
        if (status == AuditDifferenceStatus.VERIFIED || status == AuditDifferenceStatus.RESOLVED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "difference already closed: " + sourceType + "/" + sourceId);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public AuditDifferenceKind getKind() {
        return kind;
    }

    public AuditSeverity getSeverity() {
        return severity;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getReference() {
        return reference;
    }

    public Long getExpectedAmountMinor() {
        return expectedAmountMinor;
    }

    public Long getActualAmountMinor() {
        return actualAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public AuditDifferenceStatus getStatus() {
        return status;
    }

    public void setStatus(AuditDifferenceStatus status) {
        this.status = status;
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

    public long getTransferredOutMinor() {
        return transferredOutMinor;
    }

    public void setTransferredOutMinor(long transferredOutMinor) {
        this.transferredOutMinor = transferredOutMinor;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(String resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
