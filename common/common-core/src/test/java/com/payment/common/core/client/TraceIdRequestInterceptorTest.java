package com.payment.common.core.client;

import com.payment.common.core.trace.TraceContext;
import com.payment.common.core.trace.TraceIdFilter;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign traceId 透传（T072）：有 traceId 时写入 {@code X-Trace-Id} 头，无则不加。
 */
class TraceIdRequestInterceptorTest {

    private final TraceIdRequestInterceptor interceptor = new TraceIdRequestInterceptor();

    @Test
    void propagatesTraceIdHeader() {
        TraceContext.setTraceId("trace-123");
        try {
            RequestTemplate template = new RequestTemplate();
            interceptor.apply(template);

            assertThat(template.headers().get(TraceIdFilter.TRACE_ID_HEADER)).containsExactly("trace-123");
        } finally {
            TraceContext.clear();
        }
    }

    @Test
    void doesNotSetHeaderWhenTraceIdAbsent() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(TraceIdFilter.TRACE_ID_HEADER);
    }
}
