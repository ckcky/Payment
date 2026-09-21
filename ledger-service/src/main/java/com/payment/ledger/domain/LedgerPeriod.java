package com.payment.ledger.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 会计期间（spec 031 §11，G2）：{@code ledger_periods(period, currency)}；
 * 缺行 = OPEN（保守）。CLOSED 期间拒收一切新事件（含 ADJUSTMENT），更正走下期反向分录。
 */
public final class LedgerPeriod {

    private final String period;
    private final String currency;
    private final Status status;
    private final Instant closedAt;
    private final String closedBy;

    public LedgerPeriod(String period, String currency, Status status, Instant closedAt, String closedBy) {
        this.period = Objects.requireNonNull(period, "period");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = Objects.requireNonNull(status, "status");
        this.closedAt = closedAt;
        this.closedBy = closedBy;
    }

    public String getPeriod() {
        return period;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public enum Status {
        OPEN,
        CLOSED
    }
}
