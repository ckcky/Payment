package com.payment.order.web;

import com.payment.order.application.ratelimit.RateLimiter;
import com.payment.order.application.ratelimit.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 下单入口限流拦截器（014）：超限直接 429 快速失败，不返回 Retry-After（拒绝 = 不允许重试）。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final RateLimitProperties props;

    public RateLimitInterceptor(RateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!props.isEnabled()) {
            return true;
        }
        if (limiter.tryAcquire(props.getBucket(), props.getCapacity(), props.getWindowMillis())) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"rate_limit_exceeded\",\"retryable\":false}");
        return false;
    }
}
