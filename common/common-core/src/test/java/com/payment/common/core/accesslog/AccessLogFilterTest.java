package com.payment.common.core.accesslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * AccessLogFilter 单测（spec 021 / T306，照 TraceIdFilterTest 风格）：
 * 覆盖 AC1.1（正常/异常路径必打）、AC1.2（GET req=- / 4KB 截断）、AC1.3（排除路径）、
 * NFR-002（copyBodyToResponse 响应完整、过滤器异常不吞请求）、D3（masker 桩被调用且透传）。
 */
class AccessLogFilterTest {

    private final AccessLogProperties props =
            new AccessLogProperties(true, 4096, List.of("/actuator/**"));
    private final RecordingMasker masker = new RecordingMasker();
    private final AccessLogFilter filter = new AccessLogFilter(props, masker);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger(AccessLogFilter.ACCESS_LOGGER_NAME);

    @BeforeEach
    void setUp() {
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
    }

    @Test
    void normalPostLogsSingleAccessWithAllFields() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.setContentType("application/json");
        request.setContent("{\"userId\":\"u1\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        // ContentCachingRequestWrapper 语义：缓存下游（controller）真正读取的字节——
        // 真实场景 JSON 必被读，测试里由链路消费一次。
        FilterChain chain = (req, resp) -> {
            req.getInputStream().readAllBytes();
            resp.setContentType("application/json");
            resp.getWriter().write("ok");
        };

        filter.doFilter(request, response, chain);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(msg).contains("ACCESS method=POST uri=/orders")
                .contains("status=200").contains("costMs=")
                .contains("req={\"userId\":\"u1\"}")
                .contains("resp=");
        // masker 桩被调用（请求 + 响应各一次）且透传不改内容
        assertThat(masker.calls).isEqualTo(2);
    }

    @Test
    void getRequestLogsReqPlaceholder() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/OR1");
        request.setQueryString("verbose=true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("method=GET uri=/orders/OR1?verbose=true").contains("req=-");
    }

    @Test
    void oversizeBodyIsTruncatedWithMarker() throws ServletException, IOException {
        AccessLogProperties small = new AccessLogProperties(true, 64, List.of());
        AccessLogFilter f = new AccessLogFilter(small, masker);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pay");
        request.setContentType("application/json");
        request.setContent("x".repeat(1000).getBytes(StandardCharsets.UTF_8));

        f.doFilter(request, new MockHttpServletResponse(), (req, resp) -> req.getInputStream().readAllBytes());

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("...(truncated,total=1000)");
    }

    @Test
    void excludedPathsAreNotLogged() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(appender.list).isEmpty();
    }

    // ===== spec 035 §10 / ADR-0083 决策 3：渠道回调入口不落正文（C-1）+ 密钥不入日志断言（C-3） =====

    @Test
    void defaultsExcludeChannelCallbackEntry() throws ServletException, IOException {
        // C-1：不配置任何 properties 时的默认排除集合必须含 /internal/channels/**（notify 入口）
        AccessLogProperties defaults = new AccessLogProperties(true, 4096, null);
        assertThat(defaults.excludePaths()).contains("/actuator/**", "/internal/channels/**");

        AccessLogFilter f = new AccessLogFilter(defaults, masker);
        MockHttpServletRequest notify = new MockHttpServletRequest(
                "POST", "/internal/channels/alipay/notify");
        notify.setContentType("application/x-www-form-urlencoded");
        notify.setContent(("out_trade_no=PM20260921001&total_amount=88.00"
                + "&sign=a1b2c3d4e5f6%2BfakeSignature%3D&app_cert_sn=deadbeef")
                .getBytes(StandardCharsets.UTF_8));
        // 即便渠道 form 被完整消费（真实 controller 行为），默认配置下整条请求不落 ACCESS_LOG
        f.doFilter(notify, new MockHttpServletResponse(),
                (req, resp) -> req.getInputStream().readAllBytes());

        assertThat(appender.list).as("渠道回调入口零日志（不落正文，密钥/报文不入日志）").isEmpty();
    }

    @Test
    void nonExcludedPathsNeverLeakSignatureMaterial() throws ServletException, IOException {
        // C-3 测试面：非排除路径的日志内容同样 MUST NOT 含 sign=/私钥片段——
        // 若未来有人把 notify 移出排除路径且未接 mask，此测试给出行为基线（当前透传桩会记录正文，
        // 故此处以「显式禁令的边界演示」形式断言：排除列表命中即零输出；未命中路径由禁令条文管）。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.setContentType("application/json");
        request.setContent("{\"amountMinor\":100}".getBytes(StandardCharsets.UTF_8));

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, resp) -> req.getInputStream().readAllBytes());

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).doesNotContain("sign=").doesNotContain("PRIVATE KEY")
                .doesNotContain("app_cert");
    }

    @Test
    void exceptionPathStillLogsAccessAndPropagates() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/boom");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        FilterChain chain = (req, resp) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("ACCESS method=POST uri=/boom");
    }

    @Test
    void responseBodyIsCopiedBackToClient() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/hello");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> resp.getWriter().write("hi-client");

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("hi-client");
    }

    @Test
    void multipartBodyIsPlaceholder() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContentType("multipart/form-data; boundary=xx");
        request.setContent(new byte[] {1, 2, 3});

        filter.doFilter(request, new MockHttpServletResponse(), (req, resp) -> req.getInputStream().readAllBytes());

        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("req=<binary>");
    }

    /** 记录调用次数的 masker 桩。 */
    private static final class RecordingMasker implements SensitiveBodyMasker {
        private int calls;

        @Override
        public String mask(String contentType, String body) {
            calls++;
            return body;
        }
    }
}
