package com.payment.reconciliation.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 对账批次持久化实体（PO）：仅承载 reconciliation_batches 表列，
 * 匹配/差异以 JSON 文本内嵌（matches_json / differences_json），映射由仓储完成。
 */
@TableName("reconciliation_batches")
public class ReconciliationBatchEntity extends BaseEntity {

    /** 业务单号（RB + 雪花，ADR-0062）。 */
    private String batchNo;
    private String period;
    private String source;
    /** 对账状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private String matchesJson;
    private String differencesJson;
    private String closedAt;
    private String closedBy;
    private String statementSource;

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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMatchesJson() {
        return matchesJson;
    }

    public void setMatchesJson(String matchesJson) {
        this.matchesJson = matchesJson;
    }

    public String getDifferencesJson() {
        return differencesJson;
    }

    public void setDifferencesJson(String differencesJson) {
        this.differencesJson = differencesJson;
    }

    public String getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(String closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }

    public String getStatementSource() {
        return statementSource;
    }

    public void setStatementSource(String statementSource) {
        this.statementSource = statementSource;
    }
}
