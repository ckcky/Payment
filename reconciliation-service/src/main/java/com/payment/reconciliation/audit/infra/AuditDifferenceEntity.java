package com.payment.reconciliation.audit.infra;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * audit_differences 持久化实体（spec 017 / T020）。
 */
@TableName("audit_differences")
public class AuditDifferenceEntity extends BaseEntity {

    private Long batchId;
    private String kind;
    private String severity;
    private String sourceType;
    private String sourceId;
    private String reference;
    private Long expectedAmountMinor;
    private Long actualAmountMinor;
    private String currency;
    private String status;
    private Long suspendedAmountMinor;
    private Long adjustedAmountMinor;
    private String detail;
    private String resolutionNote;
    private String resolvedBy;
    private String resolvedAt;
    private Long transferredOutMinor;

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public Long getExpectedAmountMinor() {
        return expectedAmountMinor;
    }

    public void setExpectedAmountMinor(Long expectedAmountMinor) {
        this.expectedAmountMinor = expectedAmountMinor;
    }

    public Long getActualAmountMinor() {
        return actualAmountMinor;
    }

    public void setActualAmountMinor(Long actualAmountMinor) {
        this.actualAmountMinor = actualAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSuspendedAmountMinor() {
        return suspendedAmountMinor;
    }

    public void setSuspendedAmountMinor(Long suspendedAmountMinor) {
        this.suspendedAmountMinor = suspendedAmountMinor;
    }

    public Long getAdjustedAmountMinor() {
        return adjustedAmountMinor;
    }

    public void setAdjustedAmountMinor(Long adjustedAmountMinor) {
        this.adjustedAmountMinor = adjustedAmountMinor;
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

    public Long getTransferredOutMinor() {
        return transferredOutMinor;
    }

    public void setTransferredOutMinor(Long transferredOutMinor) {
        this.transferredOutMinor = transferredOutMinor;
    }
}
