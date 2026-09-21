package com.payment.reconciliation.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 对账差异台账持久化实体（PO）：reconciliation_differences 表列。
 */
@TableName("reconciliation_differences")
public class ReconciliationDifferenceEntity extends BaseEntity {

    /** 业务单号（RD + 雪花，ADR-0062）。 */
    private String diffNo;
    private Long batchId;
    private Long importId;
    private String period;
    private String merchantId;
    private String channelCode;
    private String kind;
    private String severity;
    private String referenceType;
    private String reference;
    private Long expectedAmountMinor;
    private Long actualAmountMinor;
    private Long feeAmountMinor;
    private String currency;
    private String status;
    private String dispositionRef;
    private String resolutionNote;
    private String resolvedBy;
    private String resolvedAt;

    public String getDiffNo() {
        return diffNo;
    }

    public void setDiffNo(String diffNo) {
        this.diffNo = diffNo;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getImportId() {
        return importId;
    }

    public void setImportId(Long importId) {
        this.importId = importId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
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

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
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

    public Long getFeeAmountMinor() {
        return feeAmountMinor;
    }

    public void setFeeAmountMinor(Long feeAmountMinor) {
        this.feeAmountMinor = feeAmountMinor;
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

    public String getDispositionRef() {
        return dispositionRef;
    }

    public void setDispositionRef(String dispositionRef) {
        this.dispositionRef = dispositionRef;
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
}
