package com.payment.payment.api.dto;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelResult;

/**
 * 未知支付收敛请求：携带权威结果（SUCCESS / FAILURE）。
 */
public record ResolveRequest(String result, String channelReference, String reason) {

    public ChannelResult toResult() {
        return switch (result) {
            case "SUCCESS" -> ChannelResult.success(channelReference);
            case "FAILURE" -> ChannelResult.failure(channelReference, reason);
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "invalid result: " + result);
        };
    }
}
