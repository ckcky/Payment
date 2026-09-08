package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import feign.RetryableException;
import feign.Request;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 事实读取 RPC 的局部弹性配置（ADR-0021）：仅绑定到 payment/refund 两个 facts 客户端，
 * 不注册为全局 Bean，避免污染其他 Feign 客户端。
 *
 * <p>刻意不标注 {@code @Component}/{@code @Configuration}：仅通过
 * {@code @FeignClient(configuration = FactsClientConfig.class)} 在 Feign 子上下文中生效，
 * 不会被 {@code @ComponentScan} 扫成全局配置。</p>
 *
 * <p><b>阈值外置（spec 006 T035）</b>：超时取自 {@code services.payment.connect-timeout-ms} /
 * {@code read-timeout-ms}（refund 侧同键），缺省回落 1000 / 3000。用 {@link Environment} 直接读取
 * 而非 {@code @Value}——Feign 子上下文不由 Spring Boot 创建，缺少占位符解析器，{@code @Value} 不生效。</p>
 */
public class FactsClientConfig {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 1000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 3000;

    private final Environment environment;

    public FactsClientConfig(Environment environment) {
        this.environment = environment;
    }

    /** 显式超时：connect 1s / read 3s（满足 Constitution §V.6 的显式决策要求）。 */
    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(connectTimeoutMs(), readTimeoutMs());
    }

    /** 仅对幂等只读 GET 的有限重试：3 次，退避 1s / 2s / 4s（与 ADR-0005 一致）。 */
    @Bean
    public Retryer retryer() {
        return new Retryer.Default(1000, 2000, 3);
    }

    /**
     * 错误归一化：框架异常统一为 {@link BizException#INTERNAL_ERROR}，不外泄基础设施细节。
     *
     * <p><b>可重试状态码必须抛 {@link RetryableException}（spec 006 T033 修复）</b>：Feign 只对
     * {@code RetryableException} 触发 {@link Retryer}；若这里一律返回 {@code BizException}，
     * 重试器就成了永不生效的死配置——瞬时抖动会直接判定为读失败。408 / 429 / 5xx 视为可重试；
     * 重试耗尽后由 {@code FeignPaymentFactsClient} / {@code FeignRefundFactsClient} 归一化为
     * {@code INTERNAL_ERROR}（不外泄状态码细节）。</p>
     */
    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> {
            String detail = "facts read failed: " + methodKey + " -> http " + response.status();
            if (isRetryable(response.status())) {
                feign.Request request = response.request();
                // 显式转型消除 Long(retryAfter 秒) / Date 构造重载歧义：不携带 Retry-After，按固定退避
                return new RetryableException(response.status(), detail,
                        request == null ? null : request.httpMethod(), (java.util.Date) null, request);
            }
            return new BizException(ErrorCodes.INTERNAL_ERROR, detail);
        };
    }

    /** 可重试状态码：请求超时 / 限流 / 服务端错误。 */
    static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private int connectTimeoutMs() {
        return intProperty("services.payment.connect-timeout-ms", DEFAULT_CONNECT_TIMEOUT_MS);
    }

    private int readTimeoutMs() {
        return intProperty("services.payment.read-timeout-ms", DEFAULT_READ_TIMEOUT_MS);
    }

    private int intProperty(String key, int fallback) {
        if (environment == null) {
            return fallback;
        }
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
