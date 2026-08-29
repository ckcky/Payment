package com.payment.payment.web;

import com.payment.common.core.observability.BusinessMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部端点鉴权的拒绝口径与可观测性（ADR-0024 / ADR-0037）。
 *
 * <p>直接构造拦截器（不走 Spring 上下文），聚焦两件事：</p>
 * <ol>
 *   <li>三种拒绝原因（未配置令牌 / 令牌缺失 / 令牌不匹配）分别对应 503 / 403 / 403；</li>
 *   <li>每次拒绝都带 {@code reason} 维度埋点——否则开启 {@code internal-auth-enabled} 后，
 *       「配置漂移导致的全线 503」与「真实越权 403」在监控上无法区分。</li>
 * </ol>
 */
class InternalServiceAuthInterceptorTest {

    private static final String TOKEN = "svc-token-abcdef";

    @Test
    void disabledPassesThroughWithoutRecording() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(false, "", "", metrics)
                .preHandle(new MockHttpServletRequest("POST", "/internal/payments/query-amount"), response, null);

        assertThat(proceed).isTrue();
        assertThat(metrics.events).isEmpty();
    }

    @Test
    void unconfiguredTokenIsServiceUnavailable() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true, "", "", metrics)
                .preHandle(request(null), response, null);

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(metrics.events).containsExactly(Map.entry("payment.internal_auth_rejected", "unconfigured"));
    }

    @Test
    void missingTokenIsForbidden() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true, TOKEN, "", metrics)
                .preHandle(request(null), response, null);

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(metrics.events).containsExactly(Map.entry("payment.internal_auth_rejected", "missing_token"));
    }

    @Test
    void mismatchedTokenIsForbidden() throws Exception {
        RecordingMetrics metrics = new RecordingMetrics();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true, TOKEN, "", metrics)
                .preHandle(request("wrong-token"), response, null);

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(metrics.events).containsExactly(Map.entry("payment.internal_auth_rejected", "token_mismatch"));
    }

    @Test
    void rejectsTokensOfDifferentLengthWithoutException() throws Exception {
        // 常数时间比对的长度短路分支：不同长度不得抛异常
        RecordingMetrics metrics = new RecordingMetrics();

        boolean proceed = interceptor(true, TOKEN, "", metrics)
                .preHandle(request("short"), new MockHttpServletResponse(), null);

        assertThat(proceed).isFalse();
        assertThat(metrics.events).containsExactly(Map.entry("payment.internal_auth_rejected", "token_mismatch"));
    }

    /** 服务专属令牌为空时回退到平台级令牌（ADR-0034）。 */
    @Test
    void fallsBackToPlatformToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor(true, "", "platform-shared-token", new RecordingMetrics())
                .preHandle(request("platform-shared-token"), response, null);

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static InternalServiceAuthInterceptor interceptor(boolean enabled, String serviceToken,
                                                              String platformToken, BusinessMetrics metrics) {
        return new InternalServiceAuthInterceptor(enabled, serviceToken, platformToken, metrics);
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/payments/query-amount");
        if (token != null) {
            request.addHeader("X-Service-Token", token);
        }
        return request;
    }

    private static final class RecordingMetrics implements BusinessMetrics {

        private final List<Map.Entry<String, String>> events = new ArrayList<>();

        @Override
        public void counter(String name, double value, String... tags) {
            Map<String, String> tagMap = new LinkedHashMap<>();
            for (int i = 0; i + 1 < tags.length; i += 2) {
                tagMap.put(tags[i], tags[i + 1]);
            }
            events.add(Map.entry(name, tagMap.getOrDefault("reason", "")));
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
            // 鉴权不涉及耗时指标
        }
    }
}
