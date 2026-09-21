package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 会计期间持久化实体（PO，spec 031 §11 / §14：{@code ledger_periods}）。
 * 主键 {@code (period, currency)}；缺行 = OPEN（保守）。
 */
@TableName("ledger_periods")
public class LedgerPeriodEntity {

    private String period;
    private String currency;
    private String status;
    private Instant closedAt;
    private String closedBy;

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
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

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }
}
