package com.payment.common.core.trace;

import java.util.UUID;

/**
 * 关联 ID（traceId）上下文，贯穿单次请求/用例（Constitution §6：可观测性）。
 *
 * <p>由 {@link TraceIdFilter} 在每个 HTTP 请求进入时设置；跨服务调用通过请求头传播。</p>
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /** 返回当前 traceId，不存在则临时生成一个（不写入上下文）。 */
    public static String getOrCreate() {
        String id = TRACE_ID.get();
        return id != null ? id : UUID.randomUUID().toString();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
