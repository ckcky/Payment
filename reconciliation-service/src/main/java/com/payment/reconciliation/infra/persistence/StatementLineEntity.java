package com.payment.reconciliation.infra.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 标准化账单行持久化实体（PO）：statement_lines 表列。
 *
 * <p>该表无 updated_at / created_by / updated_by / version 列（行随导入批次一次写入、不可变），
 * 故不继承 {@code BaseEntity}，created_at 由仓储写入时手工填。</p>
 */
@TableName("statement_lines")
public class StatementLineEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDateTime createdAt;

    private Long importId;
    private Integer lineNo;
    private String channelCode;
    private String channelTxnNo;
    private String referenceType;
    private String reference;
    private String referenceKind;
    private String merchantId;
    private Long amountMinor;
    private Long feeMinor;
    private String currency;
    private String status;
    private String occurredAt;
    private String rawText;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getImportId() {
        return importId;
    }

    public void setImportId(Long importId) {
        this.importId = importId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getChannelTxnNo() {
        return channelTxnNo;
    }

    public void setChannelTxnNo(String channelTxnNo) {
        this.channelTxnNo = channelTxnNo;
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

    public String getReferenceKind() {
        return referenceKind;
    }

    public void setReferenceKind(String referenceKind) {
        this.referenceKind = referenceKind;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public Long getFeeMinor() {
        return feeMinor;
    }

    public void setFeeMinor(Long feeMinor) {
        this.feeMinor = feeMinor;
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

    public String getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(String occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }
}
