package com.payment.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /payments/{id}/resolve} 收敛端点的鉴权守卫（修复 F2：未鉴权可伪造支付成功）。
 *
 * <p>该端点直接把支付翻转为 SUCCESS 并触发下游履约，属高危操作，必须鉴权。
 * 采用轻量 admin-token 校验（Phase 9 将以 Spring Security / OAuth2 统一替换）：</p>
 * <ul>
 *   <li>{@code payment.resolve.auth-enabled=false}：放行（仅限本地学习环境，日志有 WARN）。</li>
 *   <li>启用但未配置 {@code payment.resolve.admin-token}（env {@code PAYMENT_ADMIN_TOKEN}）：默认拒绝（503）。</li>
 *   <li>请求头 {@code X-Admin-Token} 与配置 token 不一致：拒绝（403）。</li>
 * </ul>
 */
@Component
public class ResolveAuthorizationInterceptor implements HandlerInterceptor {

    private final boolean authEnabled;
    private final String adminToken;

    public ResolveAuthorizationInterceptor(
            @Value("${payment.resolve.auth-enabled:true}") boolean authEnabled,
            @Value("${payment.resolve.admin-token:}") String adminToken) {
        this.authEnabled = authEnabled;
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!authEnabled) {
            return true;
        }
        if (adminToken == null || adminToken.isBlank()) {
            sendError(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "resolve endpoint locked: set PAYMENT_ADMIN_TOKEN to enable");
            return false;
        }
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || !provided.equals(adminToken)) {
            sendError(response, HttpStatus.FORBIDDEN, "missing or invalid X-Admin-Token");
            return false;
        }
        return true;
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        response.getWriter().write(body);
    }
}
