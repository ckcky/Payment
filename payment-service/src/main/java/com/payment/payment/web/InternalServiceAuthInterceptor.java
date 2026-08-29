package com.payment.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部服务间调用鉴权（ADR-0024 / FR-003）。
 *
 * <p>{@code /internal/**} 端点面向 refund / reconciliation 等兄弟服务，一旦裸露即可被越权调用
 * （如伪造退款尝试）。MVP 采用<b>共享密钥 Header</b> 这种最简方案：调用方携带
 * {@code X-Service-Token}，服务端做常数时间比对。</p>
 *
 * <ul>
 *   <li>{@code payment.security.internal-auth-enabled=false}（默认）：放行，兼容本地联调与既有测试。</li>
 *   <li>启用但未配置 {@code payment.security.service-token}（env {@code PAYMENT_INTERNAL_TOKEN}）：{@code 503}。</li>
 *   <li>Header 缺失或不一致：{@code 403}。</li>
 * </ul>
 *
 * <p>排除项：渠道回调路径由 {@link ChannelCallbackSignatureFilter} 单独验签，不在此鉴权范围内
 * （外部渠道不持有内部服务令牌）。</p>
 */
@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {

    private final boolean authEnabled;
    private final String serviceToken;

    public InternalServiceAuthInterceptor(
            @Value("${payment.security.internal-auth-enabled:false}") boolean authEnabled,
            @Value("${payment.security.service-token:}") String serviceToken) {
        this.authEnabled = authEnabled;
        this.serviceToken = serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!authEnabled) {
            return true;
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            sendError(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "internal endpoint locked: set PAYMENT_INTERNAL_TOKEN to enable");
            return false;
        }
        String provided = request.getHeader("X-Service-Token");
        if (provided == null || !constantTimeEquals(provided, serviceToken)) {
            sendError(response, HttpStatus.FORBIDDEN, "missing or invalid X-Service-Token");
            return false;
        }
        return true;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
