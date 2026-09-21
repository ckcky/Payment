package com.payment.reconciliation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.Objects;

/**
 * 对账差异台账（spec 032 §6.2 / §9 ③：从 differences_json 拆表的权威模型）。
 *
 * <p>差异为可索引、可分页查询的台账行：{@code uk_diff_identity(period, kind, reference_type,
 * reference)} 吸收同周期同类差异重复产生（§8.3 第三层幂等）。状态机：
 * {@code PENDING → SUSPENDED（挂账）→ RESOLVED（人工收口）}，处置动作失败 ⇒
 * {@code ADJUST_FAILED}（留在台账可见，不静默回退，§11 #9）。resolve 备注必填（ADR-0019）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconciliationDifference {

    private Long id;
    /** 业务单号（RD + 雪花，ADR-0062）。 */
    private String diffNo;
    private Long batchId;
    private Long importId;
    private final String period;
    private final String merchantId;
    private final String channelCode;
    private final DifferenceType kind;
    private final String referenceType;
    private final String reference;
    /** 期望金额（平台侧），可空（CHANNEL_ONLY 无平台侧）。 */
    private final Long expectedAmountMinor;
    /** 实际金额（渠道侧），可空（PLATFORM_ONLY 无渠道侧）。 */
    private final Long actualAmountMinor;
    /** 手续费差异额（FEE_MISMATCH 时非 0）。 */
    private final Long feeAmountMinor;
    private final String currency;
    private DifferenceStatus status;
    /** 处置单号（audit_adjustments.adjust_no / 挂账单）。 */
    private String dispositionRef;
    private String resolutionNote;
    private String resolvedBy;
    private String resolvedAt;

    @JsonCreator
    public ReconciliationDifference(
            @JsonProperty("diffNo") String diffNo,
            @JsonProperty("batchId") Long batchId,
            @JsonProperty("importId") Long importId,
            @JsonProperty("period") String period,
            @JsonProperty("merchantId") String merchantId,
            @JsonProperty("channelCode") String channelCode,
            @JsonProperty("kind") DifferenceType kind,
            @JsonProperty("referenceType") String referenceType,
            @JsonProperty("reference") String reference,
            @JsonProperty("expectedAmountMinor") Long expectedAmountMinor,
            @JsonProperty("actualAmountMinor") Long actualAmountMinor,
            @JsonProperty("feeAmountMinor") Long feeAmountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("status") DifferenceStatus status) {
        this.period = Objects.requireNonNull(period, "period");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.diffNo = diffNo;
        this.batchId = batchId;
        this.importId = importId;
        this.merchantId = merchantId;
        this.channelCode = channelCode;
        this.referenceType = referenceType;
        this.expectedAmountMinor = expectedAmountMinor;
        this.actualAmountMinor = actualAmountMinor;
        this.feeAmountMinor = feeAmountMinor;
        this.currency = currency == null || currency.isBlank() ? "CNY" : currency;
        this.status = status == null ? DifferenceStatus.PENDING : status;
    }

    /** 新建差异工厂：RD 单号 + 初始 PENDING（spec §7.2 DETECTED → PENDING）。 */
    public static ReconciliationDifference of(Long batchId, Long importId, String period, String merchantId,
                                              String channelCode, DifferenceType kind, String referenceType,
                                              String reference, Long expectedAmountMinor,
                                              Long actualAmountMinor, Long feeAmountMinor, String currency) {
        return new ReconciliationDifference(BusinessNos.of(BusinessNoType.RECONCILIATION_DIFFERENCE),
                batchId, importId, period, merchantId, channelCode, kind, referenceType, reference,
                expectedAmountMinor, actualAmountMinor, feeAmountMinor, currency, DifferenceStatus.PENDING);
    }

    /** 持久化重建：还原台账行全量状态（含处置与收口记录），绕过状态机（不改变业务规则）。 */
    public static ReconciliationDifference rehydrate(Long id, String diffNo, Long batchId, Long importId,
                                                     String period, String merchantId, String channelCode,
                                                     DifferenceType kind, String referenceType, String reference,
                                                     Long expectedAmountMinor, Long actualAmountMinor,
                                                     Long feeAmountMinor, String currency,
                                                     DifferenceStatus status, String dispositionRef,
                                                     String resolutionNote, String resolvedBy, String resolvedAt) {
        ReconciliationDifference difference = new ReconciliationDifference(diffNo, batchId, importId, period,
                merchantId, channelCode, kind, referenceType, reference, expectedAmountMinor, actualAmountMinor,
                feeAmountMinor, currency, status);
        difference.id = id;
        difference.dispositionRef = dispositionRef;
        difference.resolutionNote = resolutionNote;
        difference.resolvedBy = resolvedBy;
        difference.resolvedAt = resolvedAt;
        return difference;
    }

    // ---- 状态机（唯一状态变更入口）----

    /** 状态前置校验：非法前置 ⇒ STATE_TRANSITION_VIOLATION（不猜不跳）。 */
    private void requireStatus(DifferenceStatus expected, String action) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + action + " from " + this.status + ": " + diffNo);
        }
    }

    /** 挂账处置成功：登记处置单号，PENDING → SUSPENDED。 */
    public void suspend(String dispositionRef) {
        requireStatus(DifferenceStatus.PENDING, "suspend");
        this.status = DifferenceStatus.SUSPENDED;
        this.dispositionRef = dispositionRef;
    }

    /** 处置动作失败（§11 #9）：差异保持可见，登记失败处置单号；从 PENDING/SUSPENDED 均可进入。 */
    public void markAdjustFailed(String dispositionRef) {
        if (this.status == DifferenceStatus.RESOLVED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal markAdjustFailed from RESOLVED: " + diffNo);
        }
        this.status = DifferenceStatus.ADJUST_FAILED;
        if (dispositionRef != null) {
            this.dispositionRef = dispositionRef;
        }
    }

    /**
     * 人工收口（唯一处理状态变更入口）：依据 MUST 非空，并记录操作人与时间（ADR-0019）。
     * 已 RESOLVED 再次收口为幂等空操作（spec §8.3：重放不产生新状态）。
     */
    public void resolve(String note, String actor, String atIso) {
        if (note == null || note.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "resolution note must not be blank");
        }
        if (this.status == DifferenceStatus.RESOLVED) {
            return;
        }
        this.status = DifferenceStatus.RESOLVED;
        this.resolutionNote = note;
        this.resolvedBy = actor;
        this.resolvedAt = atIso;
    }

    @JsonIgnore
    public boolean isResolved() {
        return this.status == DifferenceStatus.RESOLVED;
    }

    @JsonIgnore
    public boolean isUnresolved() {
        return this.status != DifferenceStatus.RESOLVED;
    }

    /** 未收口差异对结算口径的净影响（plan §2.3）：双侧取差额，单侧缺失取该侧；FEE/重复/无法归一不影响商户口径。 */
    @JsonIgnore
    public Long settlementImpactMinor() {
        return switch (kind) {
            case PLATFORM_ONLY, STATUS_MISMATCH -> expectedAmountMinor;
            case AMOUNT_MISMATCH -> Math.abs(
                    (expectedAmountMinor == null ? 0L : expectedAmountMinor)
                            - (actualAmountMinor == null ? 0L : actualAmountMinor));
            // CHANNEL_ONLY / FEE_MISMATCH / DUPLICATE_CHANNEL / UNKNOWN_MAPPING 不进入商户结算口径
            default -> null;
        };
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setDiffNo(String diffNo) {
        this.diffNo = diffNo;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getId() {
        return id;
    }

    public String getDiffNo() {
        return diffNo;
    }

    public Long getBatchId() {
        return batchId;
    }

    public Long getImportId() {
        return importId;
    }

    public String getPeriod() {
        return period;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public DifferenceType getKind() {
        return kind;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public String getReference() {
        return reference;
    }

    public Long getExpectedAmountMinor() {
        return expectedAmountMinor;
    }

    public Long getActualAmountMinor() {
        return actualAmountMinor;
    }

    public Long getFeeAmountMinor() {
        return feeAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public DifferenceStatus getStatus() {
        return status;
    }

    public String getDispositionRef() {
        return dispositionRef;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public String getResolvedAt() {
        return resolvedAt;
    }
}
