package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import feign.Request;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * 事实读取 RPC 的局部弹性配置（ADR-0021）：仅绑定到 payment/refund 两个 facts 客户端，
 * 不注册为全局 Bean，避免污染其他 Feign 客户端。
 *
 * <p>刻意不标注 {@code @Component}/{@code @Configuration}：仅通过
 * {@code @FeignClient(configuration = FactsClientConfig.class)} 在 Feign 子上下文中生效，
 * 不会被 {@code @ComponentScan} 扫成全局配置。</p>
 */
public class FactsClientConfig {

    /** 显式超时：connect 1s / read 3s（满足 Constitution §V.6 的显式决策要求）。 */
    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(1000, 3000);
    }

    /** 仅对幂等只读 GET 的有限重试：3 次，退避 1s / 2s / 4s（与 ADR-0005 一致）。 */
    @Bean
    public Retryer retryer() {
        return new Retryer.Default(1000, 2000, 3);
    }

    /** 错误归一化：框架异常统一为 {@link BizException#INTERNAL_ERROR}，不外泄基础设施细节。 */
    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> new BizException(ErrorCodes.INTERNAL_ERROR,
                "facts read failed: " + methodKey + " -> http " + response.status());
    }
}
