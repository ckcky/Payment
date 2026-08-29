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
 * 与 {@code resolutionNote}，随批次一起持久化。不修改原始支付/退款事实。</p>
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
            @JsonProperty("resolvedAt") String resolvedAt) {
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
    }

    /** 新建差异工厂：初始未处理。 */
    public static Difference of(String reference, DifferenceType type, Long platformAmountMinor,
                                Long channelAmountMinor, String platformStatus, String channelStatus) {
        return new Difference(reference, type, platformAmountMinor, channelAmountMinor,
                platformStatus, channelStatus, null, null, null, null);
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
}
