package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import feign.Request;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * 出站 RPC 弹性配置（ADR-0023 §4）：绑定到 merchant / reconciliation 两个幂等只读 GET 客户端，
 * 不注册为全局 Bean（刻意不标注 {@code @Component}/{@code @Configuration}），避免污染其他 Feign 客户端。
 *
 * <p>connect 1s / read 3s；仅对幂等只读 GET 配置有限重试（3 次，退避 1s/2s）。错误归一化：
 * 404 保留为 {@code FeignException.NotFound}（交由适配器归一化为 {@code NOT_FOUND}，N2），
 * 其余 &ge;400 归一化为 {@code BizException#INTERNAL_ERROR}，不外泄基础设施细节。</p>
 */
public class SettlementFeignConfig {

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

    /** 错误归一化：404 交由适配器处理，其余服务端错误统一为 INTERNAL_ERROR。 */
    @Bean
    public ErrorDecoder errorDecoder() {
        ErrorDecoder defaultDecoder = new ErrorDecoder.Default();
        return (methodKey, response) -> {
            if (response.status() == 404) {
                return defaultDecoder.decode(methodKey, response);
            }
            if (response.status() >= 400) {
                return new BizException(ErrorCodes.INTERNAL_ERROR,
                        "settlement outbound rpc failed: " + methodKey + " -> http " + response.status());
            }
            return null;
        };
    }
}
