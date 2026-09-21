package com.payment.reconciliation.posting.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 台账人工入口守卫（spec 034 §9.1「与 resolve 同守卫风格」）：payment-service 的
 * {@code ResolveAuthorizationInterceptor} 同型实现（服务内自治，不跨服务 import）。
 *
 * <ul>
 *   <li>{@code payment.resolve.auth-enabled=false}：放行（仅限本地学习环境）。</li>
 *   <li>启用但未配置 {@code payment.resolve.admin-token}：默认拒绝（503）。</li>
 *   <li>请求头 {@code X-Admin-Token} 与配置 token 不一致：拒绝（403）。</li>
 * </ul>
 */
@Component
public class PostingAdminGuardInterceptor implements HandlerInterceptor {

    private final boolean authEnabled;
    private final String adminToken;

    public PostingAdminGuardInterceptor(
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
                    "pending-postings endpoint locked: set admin token to enable");
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
