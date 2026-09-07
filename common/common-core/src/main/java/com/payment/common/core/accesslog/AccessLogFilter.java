package com.payment.common.core.accesslog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 统一访问日志过滤器（spec 021 / ADR-0068，D1/D2）：每个请求<b>结束时</b>落一条
 * {@code ACCESS_LOG} INFO 日志——method/uri/status/costMs/req/resp 全字段单行（D2，
 * 放弃 IN/OUT 两条）。实现为 {@link OncePerRequestFilter} + ContentCaching 包装（D1），
 * 计时口径为整条请求链路（含序列化）。
 *
 * <p>关键纪律（NFR-002）：</p>
 * <ul>
 *   <li>不 catch 业务异常——异常交给 {@code GlobalExceptionHandler} 落 500 响应，
 *       本过滤器用 try/finally 保证 ACCESS 必打（异常路径同样必出，AC1.1）；</li>
 *   <li>{@code copyBodyToResponse()} 必须执行——ContentCachingResponseWrapper 缓存了响应体，
 *       不回写客户端将收不到响应；</li>
 *   <li>过滤器自身组装/落日志异常降级：catch 后仅 WARN，不影响请求。</li>
 * </ul>
 *
 * <p>报文口径（D4）：GET 无 body 记 {@code req=-}；multipart / 非文本 content-type 只记
 * {@code <binary>} 占位；正文经 {@link SensitiveBodyMasker} 桩后按 {@code maxBodyBytes}
 * 截断并带省略标记；排除路径（默认 {@code /actuator/**}）在 {@link #shouldNotFilter} 直接放行。</p>
 */
public class AccessLogFilter extends OncePerRequestFilter {

    /** 专用 logger 名（FR-003）：将来可独立路由（如单独 Appender / 采样）。 */
    public static final String ACCESS_LOGGER_NAME = "ACCESS_LOG";

    private static final Logger LOG = LoggerFactory.getLogger(ACCESS_LOGGER_NAME);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 无请求体占位（GET 等）。 */
    static final String NO_BODY = "-";
    /** 二进制/multipart 报文占位。 */
    static final String BINARY_PLACEHOLDER = "<binary>";

    private final AccessLogProperties properties;
    private final SensitiveBodyMasker masker;

    public AccessLogFilter(AccessLogProperties properties, SensitiveBodyMasker masker) {
        this.properties = properties;
        this.masker = masker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        for (String pattern : properties.excludePaths()) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedReq = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResp = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            filterChain.doFilter(wrappedReq, wrappedResp);
        } finally {
            // try/finally：异常路径（GlobalExceptionHandler 之外的容器级异常）同样必打 ACCESS。
            try {
                logAccess(wrappedReq, wrappedResp, elapsedMs(start));
            } catch (RuntimeException ex) {
                // 过滤器自身异常降级（NFR-002）：仅告警，不吞请求。
                LOG.warn("ACCESS log assembly failed uri={} : {}", request.getRequestURI(), ex.getMessage());
            } finally {
                // 必须回写缓存响应体，否则客户端收不到响应（NFR-002）。
                wrappedResp.copyBodyToResponse();
            }
        }
    }

    private void logAccess(ContentCachingRequestWrapper request,
                           ContentCachingResponseWrapper response, long costMs) {
        String uri = request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        String req = bodyOf(request.getContentType(), request.getContentAsByteArray(),
                request.getMethod());
        String resp = bodyOf(response.getContentType(), response.getContentAsByteArray(), null);
        LOG.info("ACCESS method={} uri={} status={} costMs={} req={} resp={}",
                request.getMethod(), uri, response.getStatus(), costMs, req, resp);
    }

    private long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /**
     * 报文取值：无 body → {@code -}；multipart / 非文本 → {@code <binary>}；
     * 文本 → UTF-8 → masker 桩 → 截断（D3/D4）。
     */
    private String bodyOf(String contentType, byte[] content, String method) {
        if (content == null || content.length == 0) {
            return NO_BODY;
        }
        if (contentType == null) {
            contentType = "";
        }
        String lower = contentType.toLowerCase();
        if (lower.startsWith("multipart/") || !isTextual(lower)) {
            return BINARY_PLACEHOLDER;
        }
        String body = masker.mask(contentType, new String(content, StandardCharsets.UTF_8));
        return truncate(body);
    }

    /** 文本类 content-type：json / xml / text / x-www-form-urlencoded。 */
    private static boolean isTextual(String lowerContentType) {
        return lowerContentType.contains("json") || lowerContentType.contains("xml")
                || lowerContentType.startsWith("text/") || lowerContentType.contains("x-www-form-urlencoded");
    }

    /** 超过 {@code maxBodyBytes} 字节截断并带省略标记（AC1.2）。 */
    private String truncate(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= properties.maxBodyBytes()) {
            return body;
        }
        String head = new String(Arrays.copyOf(bytes, properties.maxBodyBytes()), StandardCharsets.UTF_8);
        return head + "...(truncated,total=" + bytes.length + ")";
    }
}
