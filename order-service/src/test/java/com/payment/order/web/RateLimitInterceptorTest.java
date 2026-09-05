package com.payment.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.order.application.ratelimit.RateLimitProperties;
import com.payment.order.application.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 下单入口限流拦截器单测（014 收口遗留）：开关放行 / 窗口内放行 / 超限 429 快速失败
 * （响应体含 rate_limit_exceeded 且 retryable=false，不返回 Retry-After）。
 */
class RateLimitInterceptorTest {

    private final RateLimiter limiter = new RateLimiter();

    private RateLimitProperties props(boolean enabled, int capacity) {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(enabled);
        props.setCapacity(capacity);
        props.setWindowMillis(60_000L); // 窗口足够长，避免测试抖动
        props.setBucket("test-bucket");
        return props;
    }

    private HttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/orders");
    }

    @Test
    void disabledInterceptorAlwaysPasses() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, props(false, 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request(), response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request(), response, new Object())).isTrue(); // 超容量也放行
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void withinCapacityPasses() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, props(true, 3));

        for (int i = 0; i < 3; i++) {
            assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()))
                    .as("第 %d 次请求应放行", i + 1).isTrue();
        }
    }

    @Test
    void exceedingCapacityFailsFastWith429() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, props(true, 2));
        for (int i = 0; i < 2; i++) {
            interceptor.preHandle(request(), new MockHttpServletResponse(), new Object());
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request(), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString())
                .contains("\"error\":\"rate_limit_exceeded\"")
                .contains("\"retryable\":false");
        assertThat(response.getHeader("Retry-After")).isNull(); // 拒绝 = 不允许重试
    }
}
