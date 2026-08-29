package com.payment.reconciliation.api;

import com.payment.reconciliation.domain.Difference;

/**
 * 对账差异响应 DTO。差异类型用枚举名（String）暴露。
 */
public record DifferenceResponse(String reference, String type, String resolutionStatus,
                                 String resolutionNote, Long platformAmountMinor,
                                 Long channelAmountMinor, String resolvedBy, String resolvedAt) {

    public static DifferenceResponse from(Difference difference) {
        return new DifferenceResponse(
                difference.getReference(),
                difference.getType().name(),
                difference.getResolutionStatus(),
                difference.getResolutionNote(),
                difference.getPlatformAmountMinor(),
                difference.getChannelAmountMinor(),
                difference.getResolvedBy(),
                difference.getResolvedAt());
    }
}
