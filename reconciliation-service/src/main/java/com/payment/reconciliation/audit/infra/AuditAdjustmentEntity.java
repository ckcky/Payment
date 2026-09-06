package com.payment.reconciliation.audit.infra;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * audit_adjustments 持久化实体（spec 017 / T051：挂账/调账台账）。
 */
@TableName("audit_adjustments")
public class AuditAdjustmentEntity extends BaseEntity {

    private String adjustNo;
    private Long batchId;
    private Long differenceId;
    private String kind;
    private String debitAccountCode;
    private String creditAccountCode;
    private Long amountMinor;
    private String currency;
    private String postingNo;
    private String status;
    private String operator;
    private String reviewer;
    private String reason;

    public String getAdjustNo() {
        return adjustNo;
    }

    public void setAdjustNo(String adjustNo) {
        this.adjustNo = adjustNo;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getDifferenceId() {
        return differenceId;
    }

    public void setDifferenceId(Long differenceId) {
        this.differenceId = differenceId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getDebitAccountCode() {
        return debitAccountCode;
    }

    public void setDebitAccountCode(String debitAccountCode) {
        this.debitAccountCode = debitAccountCode;
    }

    public String getCreditAccountCode() {
        return creditAccountCode;
    }

    public void setCreditAccountCode(String creditAccountCode) {
        this.creditAccountCode = creditAccountCode;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPostingNo() {
        return postingNo;
    }

    public void setPostingNo(String postingNo) {
        this.postingNo = postingNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
