package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import feign.Request;
import feign.RetryableException;
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
 *
 * <p><b>⚠️ 可重试性必须与 Retryer 对齐</b>：Feign 只对 {@link RetryableException} 触发重试。
 * 若 errorDecoder 把所有错误都归一化成 {@link BizException}，下面的 {@code Retryer} 就是
 * 永不生效的死配置——瞬时抖动会被直接判为读失败（2026-09-09 修复，与 reconciliation 侧
 * {@code FactsClientConfig} 同源问题）。故对 408 / 429 / 5xx 抛 {@code RetryableException}，
 * 由适配器在重试耗尽后归一化为 {@code INTERNAL_ERROR}；4xx（含 404）一律不重试。</p>
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

    /**
     * 错误归一化：404 交由适配器处理；408 / 429 / 5xx 抛 {@link RetryableException} 以触发
     * 上面的有限重试（瞬时抖动自愈）；其余 &ge;400 归一化为 INTERNAL_ERROR 且不重试。
     */
    @Bean
    public ErrorDecoder errorDecoder() {
        ErrorDecoder defaultDecoder = new ErrorDecoder.Default();
        return (methodKey, response) -> {
            int status = response.status();
            if (status == 404) {
                return defaultDecoder.decode(methodKey, response);
            }
            if (status == 408 || status == 429 || status >= 500) {
                Request request = response.request();
                // 显式转型消除 Long(retryAfter 秒) / Date 构造重载歧义：不携带 Retry-After，按固定退避
                return new RetryableException(status,
                        "settlement outbound rpc retryable: " + methodKey + " -> http " + status,
                        request == null ? null : request.httpMethod(), (java.util.Date) null, request);
            }
            if (status >= 400) {
                return new BizException(ErrorCodes.INTERNAL_ERROR,
                        "settlement outbound rpc failed: " + methodKey + " -> http " + status);
            }
            return null;
        };
    }
}
