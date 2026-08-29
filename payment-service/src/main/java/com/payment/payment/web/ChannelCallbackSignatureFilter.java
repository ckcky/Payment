package com.payment.payment.web;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.security.SignatureVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 渠道回调验签过滤器（ADR-0025 / FR-001~FR-002）。
 *
 * <p>伪造渠道回调可直接把支付翻转为 SUCCESS 并触发履约与记账，是本系统最高危的入站面，
 * 因此验签<b>置于过滤器层、无开关、不可关闭</b>：任何未通过校验的请求都不会触达 Controller，
 * 也就不会调用 {@code PaymentCallbackService}。</p>
 *
 * <p>校验项（任一失败即 {@code 403}，且不继续过滤链）：</p>
 * <ul>
 *   <li>{@code X-Channel-Signature} 与 {@code X-Channel-Timestamp} 必须存在；</li>
 *   <li>HMAC-SHA256（验签串 {@code timestamp + "." + rawBody}）必须常数时间匹配；</li>
 *   <li>时间戳必须落在 {@code ±replayWindowMs} 内（默认 5min，防重放）。</li>
 * </ul>
 *
 * <p>未配置 {@code payment.security.channel-secret}（env {@code PAYMENT_CHANNEL_SECRET}）时返回
 * {@code 503}：验签不可关闭，配置缺失只能拒绝服务，不能静默放行（ADR-0026）。</p>
 *
 * <p><b>注册方式</b>：由 {@link WebConfig} 以 {@code FilterRegistrationBean} 显式注册
 * （url pattern 用 Servlet 前缀匹配 {@code /internal/payments/*}，具体路径在本过滤器内用
 * Ant 匹配判定）。不用 {@code @Component} 自动注册，是因为 Spring Boot 的 MockMvc 只收集
 * {@code FilterRegistrationBean}；若只注册为普通 {@code Filter} bean，集成测试会绕过验签，
 * 出现「测试全绿、生产裸奔」的假绿。</p>
 */
public class ChannelCallbackSignatureFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelCallbackSignatureFilter.class);
    private static final String MODULE = "payment";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 回调路径模式：{@code /internal/payments/{id}/channel-callback}。 */
    static final String CALLBACK_PATH_PATTERN = "/internal/payments/*/channel-callback";

    private final String channelSecret;
    private final long replayWindowMs;
    private final BusinessMetrics metrics;

    public ChannelCallbackSignatureFilter(String channelSecret, long replayWindowMs, BusinessMetrics metrics) {
        this.channelSecret = channelSecret;
        this.replayWindowMs = replayWindowMs;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !PATH_MATCHER.match(CALLBACK_PATH_PATTERN, path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (channelSecret == null || channelSecret.isBlank()) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "channel callback locked: set PAYMENT_CHANNEL_SECRET to enable", "unconfigured");
            return;
        }
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String timestamp = request.getHeader("X-Channel-Timestamp");
        String signature = request.getHeader("X-Channel-Signature");

        boolean ok = SignatureVerifier.verify(channelSecret, timestamp, body, signature,
                System.currentTimeMillis(), replayWindowMs);
        if (!ok) {
            reject(request, response, HttpStatus.FORBIDDEN, "invalid channel callback signature",
                    timestamp == null ? "missing_timestamp" : "signature_mismatch_or_replayed");
            return;
        }
        // 原始 body 已被消费，用可重复读包装器继续链路，供 @RequestBody 正常反序列化。
        chain.doFilter(new CachedBodyHttpServletRequest(request, rawBody), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                        String message, String reason) throws IOException {
        metrics.counter("payment.callback_signature_rejected", 1.0, "module", MODULE, "reason", reason);
        LOG.warn("channel callback rejected: reason={} status={} uri={}", reason, status.value(),
                request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
