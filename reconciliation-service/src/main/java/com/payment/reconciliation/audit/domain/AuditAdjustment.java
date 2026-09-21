package com.payment.reconciliation.audit.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 挂账 / 调账台账条目（FR-019 / NFR-004）：每一笔处置留痕——谁、何时、何种、金额、原因、复核人。
 * posting_no 指向 ledger 侧 {@code source_type=ADJUSTMENT} 的记账批次。
 *
 * <p>032 起（plan §2.7）：自动处置等「跨批次差异处置」允许 batchId/differenceId 为空，
 * 以 {@code diffNo}（对账差异单号 RD）回溯来源——留痕不丢，批次归属可为空。</p>
 */
public final class AuditAdjustment {

    public static final String POSTED = "POSTED";
    public static final String FAILED = "FAILED";

    private Long id;
    private final String adjustNo;
    private final Long batchId;
    private final Long differenceId;
    /** 对账差异单号（RD，032 自动处置回溯来源；常规 audit 处置为空）。 */
    private final String diffNo;
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
            @JsonProperty("diffNo") String diffNo,
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
        this.differenceId = differenceId;
        this.diffNo = diffNo;
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

    /** 旧签名兼容构造（032 前形态：无 diffNo）。 */
    public AuditAdjustment(Long id, String adjustNo, Long batchId, Long differenceId,
                           AuditAdjustmentKind kind, String debitAccountCode, String creditAccountCode,
                           long amountMinor, String currency, String postingNo, String status,
                           String operator, String reviewer, String reason) {
        this(id, adjustNo, batchId, differenceId, null, kind, debitAccountCode, creditAccountCode,
                amountMinor, currency, postingNo, status, operator, reviewer, reason);
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

    public String getDiffNo() {
        return diffNo;
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
