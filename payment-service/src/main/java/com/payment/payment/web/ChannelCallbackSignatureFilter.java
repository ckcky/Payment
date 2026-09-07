package com.payment.payment.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 渠道回调验签过滤器（ADR-0025）——<b>验签本期为空实现（占位）</b>。
 *
 * <p><b>负责人决议（2026-08-30）</b>：ADR-0025「渠道回调签名校验」改为<b>预留函数、空实现就行</b>。
 * 因此本过滤器保留完整的骨架——路径匹配、原始 body 读取、可重复读包装、拒绝分支——
 * 但 {@link #verifySignature} 恒定返回 {@code true}，回调当前一律放行。</p>
 *
 * <p>骨架之所以保留而非整个删掉：验签必须发生在<b>过滤器层</b>（未通过则不触达 Controller，
 * 也就不会调用 {@code PaymentCallbackService}），且过滤器一旦读过原始 body，
 * 就必须换上 {@link CachedBodyHttpServletRequest} 再放行，否则下游 {@code @RequestBody}
 * 会拿到已被消费的流。这两点是接入真实渠道时最容易踩的坑，空骨架把它们固化下来。</p>
 *
 * <p><b>接入真实渠道时只需实现 {@link #verifySignature}</b>：取 {@code X-Channel-Signature} /
 * {@code X-Channel-Timestamp}，用 {@code common-core} 的 {@code SignatureVerifier}
 * （HMAC-SHA256，验签串 {@code timestamp + "." + rawBody}，常数时间比对，防重放窗口）校验，
 * 失败返回 {@code false} 即走下方拒绝分支。算法与踩坑记录见
 * {@code docs/adr/0009-risk-security-decisions.md} ADR-0025。</p>
 *
 * <p><b>注册方式</b>：由 {@link WebConfig} 以 {@code FilterRegistrationBean} 显式注册
 * （url pattern 用 Servlet 前缀匹配 {@code /internal/payments/*}，具体路径在本过滤器内用
 * Ant 匹配判定）。不用 {@code @Component} 自动注册，是因为 Spring Boot 的 MockMvc 只收集
 * {@code FilterRegistrationBean}；若只注册为普通 {@code Filter} bean，集成测试会绕过过滤器，
 * 出现「测试全绿、生产行为不一致」的假绿。</p>
 */
public class ChannelCallbackSignatureFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 支付回调路径模式：{@code /internal/payments/{paymentNo}/channel-callback}。 */
    static final String CALLBACK_PATH_PATTERN = "/internal/payments/*/channel-callback";

    /** 退款回调路径模式（spec 019 / D7）：{@code /internal/refunds/{refundNo}/channel-callback}。 */
    static final String REFUND_CALLBACK_PATH_PATTERN = "/internal/refunds/*/channel-callback";

    private static final String[] CALLBACK_PATH_PATTERNS =
            {CALLBACK_PATH_PATTERN, REFUND_CALLBACK_PATH_PATTERN};

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        for (String pattern : CALLBACK_PATH_PATTERNS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String timestamp = request.getHeader("X-Channel-Timestamp");
        String signature = request.getHeader("X-Channel-Signature");

        if (!verifySignature(body, timestamp, signature)) {
            // 当前不可达（验签恒通过）；保留分支，接入验签时无需改动本方法结构。
            reject(request, response, "invalid channel callback signature");
            return;
        }
        // 原始 body 已被消费，用可重复读包装器继续链路，供 @RequestBody 正常反序列化。
        chain.doFilter(new CachedBodyHttpServletRequest(request, rawBody), response);
    }

    /**
     * 预留：渠道回调签名校验。
     *
     * <p>ADR-0025 决议本期只留空实现，故恒返回 {@code true}（放行）。</p>
     *
     * @param body 回调原始报文（验签串的组成部分）
     * @param timestamp {@code X-Channel-Timestamp} 头，防重放用
     * @param signature {@code X-Channel-Signature} 头
     * @return 恒 {@code true}
     */
    private boolean verifySignature(String body, String timestamp, String signature) {
        // TODO(ADR-0025)：接入真实渠道时在此调用 SignatureVerifier.verify(...)。当前按决议留空。
        return true;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
