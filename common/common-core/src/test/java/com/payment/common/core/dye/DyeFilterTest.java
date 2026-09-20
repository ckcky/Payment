package com.payment.common.core.dye;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link DyeFilter} 入站测试（spec 030 / T115 / FR-162、FR-165，SC-A-07）。
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li><b>非法值</b> ⇒ {@code 400} + 指标 {@code dye_tag_rejected_total{reason=invalid}}，
 *       <b>不静默回落</b>（ADR-0049：配错不许静默走默认）；</li>
 *   <li><b>正常值</b> ⇒ {@link DyeContext} 与 MDC 均就位、响应头回写
 *       {@code X-Dye-Tag: <mode>}；</li>
 *   <li><b>{@code finally} 清理</b> ⇒ 请求结束后 MDC 与 {@link DyeContext} 都不残留
 *       （线程池复用下残留 = 染色污染下一个请求）。</li>
 * </ol>
 */
class DyeFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DyeFilter filter = new DyeFilter(new MicrometerBusinessMetrics(registry));

    @AfterEach
    void tearDown() {
        DyeContext.clear();
        MDC.remove(DyeFilter.MDC_KEY);
    }

    @Test
    void missingHeaderDefaultsToMockAndEchoesMock() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<DyeMode> seen = new AtomicReference<>();

        filter.doFilter(request, response, capture(seen));

        // 缺省 = MOCK（安全默认）：链路内读到 MOCK，响应头也回写 MOCK
        // （注意与出站 DyeRequestInterceptor 的**刻意非对称**：出站未染色时**不写头**，
        //   不把缺省语义硬编码进跨服务协议；入站回写仅面向本请求的调用方，属诊断信息）
        assertThat(seen.get()).isEqualTo(DyeMode.MOCK);
        assertThat(response.getHeader(DyeFilter.DYE_HEADER)).isEqualTo("MOCK");
        assertThat(response.getStatus()).isEqualTo(200);
        // 未染色不算拒绝
        assertThat(registry.find("dye_tag_rejected_total").counter()).isNull();
    }

    @Test
    void sandboxHeaderSetsContextMdcAndEchoesResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.addHeader(DyeFilter.DYE_HEADER, "SANDBOX");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<DyeMode> seen = new AtomicReference<>();
        AtomicReference<String> mdcSeen = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) -> {
            seen.set(DyeContext.current());
            mdcSeen.set(MDC.get(DyeFilter.MDC_KEY));
        });

        assertThat(seen.get()).isEqualTo(DyeMode.SANDBOX);
        assertThat(mdcSeen.get()).isEqualTo("SANDBOX");
        // 响应头回写当前值，便于客户端确认染色已生效
        assertThat(response.getHeader(DyeFilter.DYE_HEADER)).isEqualTo("SANDBOX");
    }

    @Test
    void parsingIsCaseInsensitiveAtTheFilterBoundary() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(DyeFilter.DYE_HEADER, " sandbox ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<DyeMode> seen = new AtomicReference<>();

        filter.doFilter(request, response, capture(seen));

        assertThat(seen.get()).isEqualTo(DyeMode.SANDBOX);
        assertThat(response.getHeader(DyeFilter.DYE_HEADER)).isEqualTo("SANDBOX");
    }

    @Test
    void invalidValueIsRejectedWith400AndMetricAndDoesNotReachChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.addHeader(DyeFilter.DYE_HEADER, "SANBOX");   // 拼错
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<DyeMode> reached = new AtomicReference<>();

        filter.doFilter(request, response, capture(reached));

        // ① 400 + INVALID_ARGUMENT：绝不静默回落 mock
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("INVALID_ARGUMENT").contains("SANBOX");
        // ② 指标 reason=invalid +1
        assertThat(registry.get("dye_tag_rejected_total").tag("reason", "invalid")
                .counter().count()).isEqualTo(1.0);
        // ③ 请求**未**抵达下游链路（fail fast，不带着错染色继续跑）
        assertThat(reached.get()).isNull();
    }

    @Test
    void multipleInvalidRequestsAccumulateMetric() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.addHeader(DyeFilter.DYE_HEADER, "PROD");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        assertThat(registry.get("dye_tag_rejected_total").tag("reason", "invalid")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void contextAndMdcAreClearedAfterRequestEvenOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        request.addHeader(DyeFilter.DYE_HEADER, "SANDBOX");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, resp) -> {
                throw new IllegalStateException("downstream boom");
            });
        } catch (Exception ignored) {
            // 异常向上传播属预期；本用例只验证 finally 清理
        }

        // finally 清理：线程复用下残留 = 染色污染下一个请求
        // current() 未染色返回 null（调用方按 MOCK 处理，见 DyeContext 契约）
        assertThat(DyeContext.current()).isNull();
        assertThat(DyeContext.isSandbox()).isFalse();
        assertThat(MDC.get(DyeFilter.MDC_KEY)).isNull();
    }

    /** 捕获链路内观察到的染色模态（FilterChain 是函数式接口，直接用 lambda）。 */
    private static FilterChain capture(AtomicReference<DyeMode> sink) {
        return (req, resp) -> sink.set(DyeContext.current());
    }
}
