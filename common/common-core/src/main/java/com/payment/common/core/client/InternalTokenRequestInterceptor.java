package com.payment.common.core.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 出站内部服务令牌传播（ADR-0034）。
 *
 * <p>背景：{@code payment-service} 的 {@code /internal/**} 端点已由
 * {@code InternalServiceAuthInterceptor} 以 {@code X-Service-Token} 守卫（ADR-0024），
 * 但开关一旦置 {@code true}，所有调用方若不带令牌就会被全线 {@code 403}。本拦截器即为该开关的
 * <b>前置依赖</b>：让调用方在出站 RPC 上自动补上共享令牌，从而使
 * {@code payment.security.internal-auth-enabled=true} 可以安全开启。</p>
 *
 * <p>三条最简约定：</p>
 * <ol>
 *   <li><b>只给内部端点发令牌</b>：仅当请求目标含 {@code /internal/} 段时才附加头。外部渠道/第三方
 *       URL 一律不发，避免把内网共享密钥泄漏到平台之外（这是本 ADR 的核心安全边界）。</li>
 *   <li><b>默认关闭</b>：{@code platform.security.outbound-token-enabled} 默认 {@code false}，
 *       未配置令牌时同样不加头——保持既有本地联调与集成测试不被破坏。</li>
 *   <li><b>不覆盖已有头</b>：调用方显式设置了 {@code X-Service-Token} 时保留原值。</li>
 * </ol>
 *
 * <p>令牌为全平台共享的一把（env {@code PLATFORM_INTERNAL_TOKEN}），与各服务进程通过同一配置项
 * 下发；如需按服务拆分密钥见 ADR-0034 待确认项。</p>
 */
public class InternalTokenRequestInterceptor implements RequestInterceptor {

    /** 内部服务共享令牌的请求头名，与入站 {@code InternalServiceAuthInterceptor} 保持一致。 */
    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    /**
     * 内部端点路径前缀。出站请求目标包含该段才附加令牌；
     * 渠道回调等外部面以及 {@code /payments}、{@code /skus} 等对外 API 均不附加。
     */
    public static final String INTERNAL_PATH_SEGMENT = "/internal/";

    private final boolean enabled;
    private final String serviceToken;

    public InternalTokenRequestInterceptor(boolean enabled, String serviceToken) {
        this.enabled = enabled;
        this.serviceToken = serviceToken;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (!enabled || serviceToken == null || serviceToken.isBlank()) {
            return;
        }
        if (!isInternalTarget(template)) {
            return;
        }
        if (template.headers().containsKey(SERVICE_TOKEN_HEADER)) {
            return;
        }
        template.header(SERVICE_TOKEN_HEADER, serviceToken);
    }

    /**
     * 判定是否为内部端点目标。
     *
     * <p>Feign 在不同阶段把路径放在 {@code url} 或 {@code path} 上：基于服务名的客户端
     * （{@code @FeignClient(name=...)}）在拦截器阶段尚未解析出绝对 URL，路径留在 {@code url}；
     * 显式 {@code url=...} 的客户端则可能已拼好。此处两者都看，取或，保证任一形态都识别得到。</p>
     */
    private static boolean isInternalTarget(RequestTemplate template) {
        return containsInternalSegment(template.url()) || containsInternalSegment(template.path());
    }

    private static boolean containsInternalSegment(String value) {
        return value != null && value.contains(INTERNAL_PATH_SEGMENT);
    }
}
