package com.payment.common.core.dye;

import com.payment.common.core.observability.BusinessMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 染色入站过滤器（spec 030 / ADR-0076，FR-162）：读 {@code X-Dye-Tag} → 解析 →
 * 写入 {@link DyeContext} 与 MDC → 响应头回写 → {@code finally} 清理。
 *
 * <h3>非法值 fail fast（ADR-0049 第 2 条：配错不许静默走默认）</h3>
 * 非法取值 ⇒ {@code 400} + 指标 {@code dye_tag_rejected_total{reason=invalid}}，
 * <b>不静默回落</b> MOCK——把 {@code SANDBOX} 拼错成 {@code SANBOX} 却悄悄走 mock，
 * 会让「我明明在测沙箱」的排查彻底失控。
 *
 * <h3>INV-5：入站读与出站写 MUST 同批落地</h3>
 * 只加入站、不做出站 ⇒ 下游服务读不到染色 ⇒ 全线按未染色处理（历史教训：
 * {@code InternalToken} 曾因只做入站校验导致全线 403）。本类与
 * {@link DyeRequestInterceptor} 必须同批提交。
 *
 * <p>过滤链定序：{@code TraceIdFilter(-200)} → {@code DyeFilter(-190)} →
 * {@code AccessLogFilter(-100)}（FR-165）。</p>
 */
public class DyeFilter extends OncePerRequestFilter {

    /** 染色头名。 */
    public static final String DYE_HEADER = "X-Dye-Tag";
    /** MDC key。 */
    public static final String MDC_KEY = "dyeMode";

    private final BusinessMetrics metrics;

    public DyeFilter(BusinessMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String raw = request.getHeader(DYE_HEADER);
        DyeMode mode;
        try {
            mode = DyeMode.parse(raw); // null/空白 ⇒ MOCK；非法 ⇒ 抛异常
        } catch (IllegalArgumentException ex) {
            metrics.counter("dye_tag_rejected_total", 1.0, "reason", "invalid");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"INVALID_ARGUMENT\",\"message\":\"invalid "
                    + DYE_HEADER + ": " + raw + " (expected MOCK or SANDBOX)\"}");
            return;
        }

        DyeContext.set(mode);
        MDC.put(MDC_KEY, mode.name());
        try {
            response.setHeader(DYE_HEADER, mode.name());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            DyeContext.clear();
        }
    }
}
