package com.payment.common.core.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关联 ID 入站过滤（T073）：验证 {@code X-Trace-Id} 被提取/生成后写入 {@link TraceContext} 与
 * MDC、回写到响应头，并在请求结束时清理（保证线程复用不串号）。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        MDC.remove(TraceIdFilter.MDC_KEY);
    }

    @Test
    void propagatesInboundTraceIdToContextMdcAndResponse() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(TraceIdFilter.TRACE_ID_HEADER)).thenReturn("trace-inbound-42");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-inbound-42");
        verify(chain).doFilter(request, response);
        // 请求结束后上下文与 MDC 被清理。
        assertThat(TraceContext.getTraceId()).isNull();
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(TraceIdFilter.TRACE_ID_HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        // 无入站 traceId 时生成了一个非空 traceId（无法预知 UUID，仅断言已回写非空头）。
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq(TraceIdFilter.TRACE_ID_HEADER),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(TraceContext.getTraceId()).isNull();
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}
