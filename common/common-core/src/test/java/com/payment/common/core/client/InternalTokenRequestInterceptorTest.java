package com.payment.common.core.client;

import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站内部服务令牌传播（ADR-0034）。
 *
 * <p>核心不变量：<b>令牌只能发给 {@code /internal/} 端点</b>，绝不外泄到渠道/第三方 URL；
 * 且默认（未开启开关或未配置令牌）保持完全静默，不破坏既有调用。</p>
 */
class InternalTokenRequestInterceptorTest {

    private static final String TOKEN = "svc-token-abcdef";

    private static InternalTokenRequestInterceptor interceptor(boolean enabled, String token) {
        return new InternalTokenRequestInterceptor(enabled, token);
    }

    private static RequestTemplate template(String path) {
        return new RequestTemplate().uri(path);
    }

    @Test
    void addsServiceTokenHeaderForInternalTarget() {
        RequestTemplate template = template("/internal/ledger/postings");

        interceptor(true, TOKEN).apply(template);

        assertThat(template.headers().get(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER))
                .containsExactly(TOKEN);
    }

    @Test
    void doesNotLeakTokenToExternalTargets() {
        // 渠道/对外 API：绝不能携带内网共享密钥
        RequestTemplate channel = template("/payments/1/channel-callback");
        RequestTemplate publicApi = template("/payments");
        RequestTemplate sku = template("/skus/1");

        InternalTokenRequestInterceptor active = interceptor(true, TOKEN);
        active.apply(channel);
        active.apply(publicApi);
        active.apply(sku);

        assertThat(channel.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
        assertThat(publicApi.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
        assertThat(sku.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
    }

    @Test
    void staysSilentWhenDisabled() {
        RequestTemplate template = template("/internal/ledger/postings");

        interceptor(false, TOKEN).apply(template);

        assertThat(template.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
    }

    @Test
    void staysSilentWhenTokenNotConfigured() {
        RequestTemplate blank = template("/internal/ledger/postings");
        interceptor(true, "").apply(blank);

        RequestTemplate nullToken = template("/internal/ledger/postings");
        interceptor(true, null).apply(nullToken);

        assertThat(blank.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
        assertThat(nullToken.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
    }

    @Test
    void doesNotOverwriteExplicitlyProvidedHeader() {
        RequestTemplate template = template("/internal/ledger/postings");
        template.header(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER, "caller-supplied");

        interceptor(true, TOKEN).apply(template);

        assertThat(template.headers().get(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER))
                .containsExactly("caller-supplied");
    }

    @Test
    void matchesInternalSegmentRatherThanPrefixOnly() {
        // 反例：/internal-payments 不是内部端点段，不应命中
        RequestTemplate template = template("/internal-payments/query");

        interceptor(true, TOKEN).apply(template);

        assertThat(template.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
    }
}
