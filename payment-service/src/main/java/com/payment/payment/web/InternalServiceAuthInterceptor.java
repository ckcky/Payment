package com.payment.payment.web;

import com.payment.common.core.observability.BusinessMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(InternalServiceAuthInterceptor.class);
    private static final String MODULE = "payment";

    private final boolean authEnabled;
    private final String serviceToken;
    private final BusinessMetrics metrics;

    /**
     * @param authEnabled 鉴权开关（默认 false）
     * @param serviceToken 本服务专属令牌 {@code payment.security.service-token}（env {@code PAYMENT_INTERNAL_TOKEN}）
     * @param platformToken 平台级共享令牌 {@code platform.security.internal-token}（env {@code PLATFORM_INTERNAL_TOKEN}），
     *                      正是出站 {@code InternalTokenRequestInterceptor} 携带的那一把。两者取首个非空值，
     *                      使入站与出站同源，{@code internal-auth-enabled=true} 才可能安全开启（ADR-0034）。
     *
     *                      <p><b>为什么不用 {@code ${a:${b:}}} 嵌套默认值</b>：YAML 中
     *                      {@code service-token: ${PAYMENT_INTERNAL_TOKEN:}} 在环境变量缺失时解析为
     *                      <em>空字符串</em>而非“未定义”，而 Spring 对空字符串视作已配置、不会回退到
     *                      嵌套默认值，导致回退静默失效（会以 503 拒绝全部内部调用）。故在 Java 侧显式判空。</p>
     * @param metrics 拒绝埋点（ADR-0037）：区分「配置缺失 503」与「真实越权 403」，否则开启开关后出问题无从观测。
     */
    public InternalServiceAuthInterceptor(
            @Value("${payment.security.internal-auth-enabled:false}") boolean authEnabled,
            @Value("${payment.security.service-token:}") String serviceToken,
            @Value("${platform.security.internal-token:}") String platformToken,
            BusinessMetrics metrics) {
        this.authEnabled = authEnabled;
        this.serviceToken = firstNonBlank(serviceToken, platformToken);
        this.metrics = metrics;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!authEnabled) {
            return true;
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "internal endpoint locked: set PAYMENT_INTERNAL_TOKEN or PLATFORM_INTERNAL_TOKEN to enable",
                    "unconfigured");
            return false;
        }
        String provided = request.getHeader("X-Service-Token");
        if (provided == null) {
            reject(request, response, HttpStatus.FORBIDDEN, "missing or invalid X-Service-Token", "missing_token");
            return false;
        }
        if (!constantTimeEquals(provided, serviceToken)) {
            reject(request, response, HttpStatus.FORBIDDEN, "missing or invalid X-Service-Token", "token_mismatch");
            return false;
        }
        return true;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                        String message, String reason) throws IOException {
        metrics.counter("payment.internal_auth_rejected", 1.0, "module", MODULE, "reason", reason);
        LOG.warn("internal endpoint rejected: reason={} status={} uri={}", reason, status.value(),
                request.getRequestURI());
        sendError(response, status, message);
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
