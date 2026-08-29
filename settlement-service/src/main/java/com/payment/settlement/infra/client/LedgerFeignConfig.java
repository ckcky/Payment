package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import feign.Request;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * 记账出站 RPC 弹性配置（ADR-0023 §4）：绑定到 ledger 客户端（含记账 POST）。
 *
 * <p>刻意<b>不配置</b> {@code Retryer}（feign 默认 {@code NEVER_RETRY}），因为记账 POST 非幂等安全重试，
 * 必须只靠幂等键 + 待记账兜底（ADR-0023）。connect 1s / read 3s；404 保留、其余 &ge;400 归一化为 INTERNAL_ERROR。</p>
 */
public class LedgerFeignConfig {

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(1000, 3000);
    }

    @Bean
    public ErrorDecoder errorDecoder() {
        ErrorDecoder defaultDecoder = new ErrorDecoder.Default();
        return (methodKey, response) -> {
            if (response.status() == 404) {
                return defaultDecoder.decode(methodKey, response);
            }
            if (response.status() >= 400) {
                return new BizException(ErrorCodes.INTERNAL_ERROR,
                        "settlement ledger rpc failed: " + methodKey + " -> http " + response.status());
            }
            return null;
        };
    }
}
