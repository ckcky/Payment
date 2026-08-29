package com.payment.payment.api.dto;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 渠道回调入站请求（ADR-0025）。
 *
 * <p>与 {@link ResolveRequest} 的差异：回调是渠道<b>主动推送</b>，可能给出 UNKNOWN
 * （如渠道侧超时补推的无结论通知），因此状态取值放宽为 {@code SUCCESS|FAILURE|UNKNOWN}；
 * 收敛端点则是人工裁定，只接受明确的 SUCCESS / FAILURE。</p>
 *
 * <p>{@code amountMinor} 为渠道回传的实付金额（最小货币单位），当前仅落观测不做拦截
 * （金额校验属于对账能力，见 Feature 004）。</p>
 */
public record ChannelCallbackRequest(
        @NotNull @Pattern(regexp = "SUCCESS|FAILURE|UNKNOWN",
                message = "status must be SUCCESS, FAILURE or UNKNOWN")
        String status,
        String channelReference,
        String reason,
        Long amountMinor) {

    public ChannelResult toResult() {
        return switch (status) {
            case "SUCCESS" -> ChannelResult.success(channelReference);
            case "FAILURE" -> ChannelResult.businessFailure(channelReference, reason);
            case "UNKNOWN" -> ChannelResult.businessUnknown(reason);
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "invalid status: " + status);
        };
    }
}
