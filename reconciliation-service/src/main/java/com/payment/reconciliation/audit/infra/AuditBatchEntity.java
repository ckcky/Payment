package com.payment.reconciliation.audit.infra;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * audit_batches 持久化实体（spec 017 / T020）。
 */
@TableName("audit_batches")
public class AuditBatchEntity extends BaseEntity {

    private String batchNo;
    private String period;
    private String scope;
    private String status;
    private Integer checkedCount;
    private Long suspendedAmountMinor;
    private Long adjustedAmountMinor;
    private String triggeredBy;
    private String startedAt;
    private String finishedAt;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCheckedCount() {
        return checkedCount;
    }

    public void setCheckedCount(Integer checkedCount) {
        this.checkedCount = checkedCount;
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

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
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

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }
}
