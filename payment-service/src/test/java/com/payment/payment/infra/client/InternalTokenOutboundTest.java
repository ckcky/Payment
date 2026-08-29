package com.payment.payment.infra.client;

import com.payment.common.core.client.InternalTokenRequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站内部服务令牌的装配验证（ADR-0034 / 009 T013）。
 *
 * <p>路径匹配逻辑本身由 common-core 的 {@code InternalTokenRequestInterceptorTest} 覆盖；
 * 本类验证的是<b>装配</b>：{@code FeignInternalTokenAutoConfiguration} 是否真的在服务上下文里
 * 生效，以及 {@code platform.security.*} 配置是否正确绑定到 bean 上。缺了这一环，就可能出现
 * 「配置写了但拦截器没注册」的静默失效。</p>
 *
 * <p>另一条关键不变量：<b>默认上下文必须是静默的</b>——拦截器存在但不加任何头，
 * 否则既有本地联调与集成测试会被打破。</p>
 */
class InternalTokenOutboundTest {

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            "platform.security.outbound-token-enabled=true",
            "platform.security.internal-token=platform-shared-token"
    })
    class Enabled {

        @Autowired
        private InternalTokenRequestInterceptor interceptor;

        @Test
        void isAutoConfiguredAndAddsTokenForInternalPath() {
            RequestTemplate template = new RequestTemplate().uri("/internal/ledger/postings");

            interceptor.apply(template);

            assertThat(template.headers().get(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER))
                    .containsExactly("platform-shared-token");
        }

        @Test
        void doesNotLeakTokenToExternalPath() {
            RequestTemplate template = new RequestTemplate().uri("/payments");

            interceptor.apply(template);

            assertThat(template.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
        }
    }

    @Nested
    @SpringBootTest
    class DefaultConfiguration {

        @Autowired
        private InternalTokenRequestInterceptor interceptor;

        @Test
        void staysSilentByDefault() {
            RequestTemplate template = new RequestTemplate().uri("/internal/ledger/postings");

            interceptor.apply(template);

            assertThat(template.headers()).doesNotContainKey(InternalTokenRequestInterceptor.SERVICE_TOKEN_HEADER);
        }
    }
}
