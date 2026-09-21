package com.payment.reconciliation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.Objects;

/**
 * 对账差异：平台与渠道在某个引用上不一致，或只有单侧存在。
 *
 * <p>差异是可解释、可处理（resolve）的事实；处理状态记录在 {@code resolutionStatus}
 * 与 {@code resolutionNote}。批次内视图：032 起新批次差异的权威台账在
 * {@code reconciliation_differences}（{@link ReconciliationDifference}，携带 diffNo/merchantId），
 * 本类型保留为聚合内快照视图（differences_json 停写不停读：legacy 批次 JSON 仍可反序列化，
 * 新批次由台账水合）。不修改原始支付/退款事实。</p>
 */
public final class Difference {

    public static final String RESOLVED = "RESOLVED";

    private final String reference;
    private final DifferenceType type;
    private final Long platformAmountMinor;
    private final Long channelAmountMinor;
    private final String platformStatus;
    private final String channelStatus;
    private String resolutionStatus;
    private String resolutionNote;
    private String resolvedBy;
    private String resolvedAt;
    /** 差异台账单号（RD 前缀，032 起的新差异携带；legacy JSON 无此字段 → null）。 */
    private String diffNo;
    /** 商户号（032/G2 匹配键维度；legacy 差异无此字段 → null）。 */
    private String merchantId;
    /** 渠道行类型（PAYMENT/REFUND/…，台账 reference_type 列；legacy 无 → null）。 */
    private String referenceType;
    /** 手续费差异额（FEE_MISMATCH 时 = 渠道行手续费；其余 → null）。 */
    private Long feeAmountMinor;

    @JsonCreator
    public Difference(
            @JsonProperty("reference") String reference,
            @JsonProperty("type") DifferenceType type,
            @JsonProperty("platformAmountMinor") Long platformAmountMinor,
            @JsonProperty("channelAmountMinor") Long channelAmountMinor,
            @JsonProperty("platformStatus") String platformStatus,
            @JsonProperty("channelStatus") String channelStatus,
            @JsonProperty("resolutionStatus") String resolutionStatus,
            @JsonProperty("resolutionNote") String resolutionNote,
            @JsonProperty("resolvedBy") String resolvedBy,
            @JsonProperty("resolvedAt") String resolvedAt,
            @JsonProperty("diffNo") String diffNo,
            @JsonProperty("merchantId") String merchantId,
            @JsonProperty("referenceType") String referenceType,
            @JsonProperty("feeAmountMinor") Long feeAmountMinor) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.type = Objects.requireNonNull(type, "type");
        this.platformAmountMinor = platformAmountMinor;
        this.channelAmountMinor = channelAmountMinor;
        this.platformStatus = platformStatus;
        this.channelStatus = channelStatus;
        this.resolutionStatus = resolutionStatus;
        this.resolutionNote = resolutionNote;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.diffNo = diffNo;
        this.merchantId = merchantId;
        this.referenceType = referenceType;
        this.feeAmountMinor = feeAmountMinor;
    }

    /** 新建差异工厂：初始未处理。 */
    public static Difference of(String reference, DifferenceType type, Long platformAmountMinor,
                                Long channelAmountMinor, String platformStatus, String channelStatus) {
        return new Difference(reference, type, platformAmountMinor, channelAmountMinor,
                platformStatus, channelStatus, null, null, null, null, null, null, null, null);
    }

    /** 新建差异工厂（032 typed 通道：带商户与渠道行类型维度）。 */
    public static Difference typed(String reference, DifferenceType type, Long platformAmountMinor,
                                   Long channelAmountMinor, String platformStatus, String channelStatus,
                                   String merchantId, String referenceType, Long feeAmountMinor) {
        return new Difference(reference, type, platformAmountMinor, channelAmountMinor,
                platformStatus, channelStatus, null, null, null, null, null, merchantId, referenceType,
                feeAmountMinor);
    }

    /** 标记为已处理（唯一处理状态变更入口）：依据 MUST 非空，并记录操作人与时间（ADR-0019）。 */
    public void resolve(String note, String actor, String atIso) {
        if (note == null || note.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "resolution note must not be blank");
        }
        this.resolutionStatus = RESOLVED;
        this.resolutionNote = note;
        this.resolvedBy = actor;
        this.resolvedAt = atIso;
    }

    @JsonIgnore
    public boolean isResolved() {
        return RESOLVED.equals(this.resolutionStatus);
    }

    public String getReference() {
        return reference;
    }

    public DifferenceType getType() {
        return type;
    }

    public Long getPlatformAmountMinor() {
        return platformAmountMinor;
    }

    public Long getChannelAmountMinor() {
        return channelAmountMinor;
    }

    public String getPlatformStatus() {
        return platformStatus;
    }

    public String getChannelStatus() {
        return channelStatus;
    }

    public String getResolutionStatus() {
        return resolutionStatus;
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

    public String getDiffNo() {
        return diffNo;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public Long getFeeAmountMinor() {
        return feeAmountMinor;
    }
}
