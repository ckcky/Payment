package com.payment.common.core.client;

import com.payment.common.core.trace.TraceContext;
import com.payment.common.core.trace.TraceIdFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 链路透传：把当前 {@link TraceContext} 中的 traceId 写入 {@code X-Trace-Id} 请求头，
 * 向下游 RPC 传播关联 ID（Constitution §6：跨服务调用用 traceId 串联）。
 *
 * <p>仅当 classpath 存在 Feign 时由自动配置装配（feign-core 为 OPTIONAL 依赖）。</p>
 */
public class TraceIdRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = TraceContext.getTraceId();
        if (traceId != null && !traceId.isBlank()) {
            template.header(TraceIdFilter.TRACE_ID_HEADER, traceId);
        }
    }
}
