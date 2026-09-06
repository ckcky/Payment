package com.payment.reconciliation.audit.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 挂账 / 调账台账条目（FR-019 / NFR-004）：每一笔处置留痕——谁、何时、何种、金额、原因、复核人。
 * posting_no 指向 ledger 侧 {@code source_type=ADJUSTMENT} 的记账批次。
 */
public final class AuditAdjustment {

    public static final String POSTED = "POSTED";

    private Long id;
    private final String adjustNo;
    private final Long batchId;
    private final Long differenceId;
    private final AuditAdjustmentKind kind;
    private final String debitAccountCode;
    private final String creditAccountCode;
    private final long amountMinor;
    private final String currency;
    private String postingNo;
    private String status;
    private final String operator;
    private final String reviewer;
    private final String reason;

    @JsonCreator
    public AuditAdjustment(
            @JsonProperty("id") Long id,
            @JsonProperty("adjustNo") String adjustNo,
            @JsonProperty("batchId") Long batchId,
            @JsonProperty("differenceId") Long differenceId,
            @JsonProperty("kind") AuditAdjustmentKind kind,
            @JsonProperty("debitAccountCode") String debitAccountCode,
            @JsonProperty("creditAccountCode") String creditAccountCode,
            @JsonProperty("amountMinor") long amountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("postingNo") String postingNo,
            @JsonProperty("status") String status,
            @JsonProperty("operator") String operator,
            @JsonProperty("reviewer") String reviewer,
            @JsonProperty("reason") String reason) {
        this.id = id;
        this.adjustNo = Objects.requireNonNull(adjustNo, "adjustNo");
        this.batchId = batchId;
        this.differenceId = Objects.requireNonNull(differenceId, "differenceId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.debitAccountCode = Objects.requireNonNull(debitAccountCode, "debitAccountCode");
        this.creditAccountCode = Objects.requireNonNull(creditAccountCode, "creditAccountCode");
        this.amountMinor = amountMinor;
        this.currency = currency == null ? "CNY" : currency;
        this.postingNo = postingNo;
        this.status = status == null ? POSTED : status;
        this.operator = Objects.requireNonNull(operator, "operator");
        this.reviewer = reviewer;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAdjustNo() {
        return adjustNo;
    }

    public Long getBatchId() {
        return batchId;
    }

    public Long getDifferenceId() {
        return differenceId;
    }

    public AuditAdjustmentKind getKind() {
        return kind;
    }

    public String getDebitAccountCode() {
        return debitAccountCode;
    }

    public String getCreditAccountCode() {
        return creditAccountCode;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
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

    public String getOperator() {
        return operator;
    }

    public String getReviewer() {
        return reviewer;
    }

    public String getReason() {
        return reason;
    }
}
