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
