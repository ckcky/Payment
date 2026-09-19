package com.payment.common.core.trace;

import java.util.UUID;
import org.slf4j.MDC;

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

    /**
     * 在新 traceId 下执行一段逻辑并确保清理（spec 021 / FR-005，D7）：供定时任务 /
     * 后台线程入口使用——调度线程不经过 {@link TraceIdFilter}，入口须自行生成 traceId，
     * 否则后台日志全部 traceId=N/A，无法被 trace-grep 捞取。
     *
     * <p>收口样板代码：入口生成/复用 traceId → MDC 同步 → 执行 → finally 清理（AC3.2 用后清理）。
     * 若当前线程已有 traceId（如调度线程被外层手动包过）则复用，不另起新链。</p>
     */
    public static void runWithNewTrace(Runnable action) {
        String previous = TRACE_ID.get();
        String traceId = previous != null ? previous : UUID.randomUUID().toString();
        setTraceId(traceId);
        MDC.put(TraceIdFilter.MDC_KEY, traceId);
        try {
            action.run();
        } finally {
            // 恢复外层上下文（嵌套包装不破坏外层链路）；无外层则用后必清（AC3.2）。
            if (previous != null) {
                setTraceId(previous);
                MDC.put(TraceIdFilter.MDC_KEY, previous);
            } else {
                MDC.remove(TraceIdFilter.MDC_KEY);
                clear();
            }
        }
    }

    /** MDC 中的业务单号键（spec 029 / FR-605）：orderNo / paymentNo / refundNo。 */
    public static final String BIZ_NO_KEY = "bizNo";

    /**
     * 在指定 bizNo 下执行一段逻辑并确保清理（spec 029 / FR-605、FR-606）。
     *
     * <p><b>为什么需要单独的 bizNo 维度</b>：traceId 能串起一条链路，但排障时要回答的往往是
     * 「这**一笔订单/支付单**发生了什么」——traceId 会随每次入口请求而变（同一订单被查询三次
     * 就是三个 traceId），只有 bizNo 是跨请求稳定的。两者互补，缺一不可。</p>
     *
     * <p>消费侧由 {@code StreamConsumer} 从信封恢复；生产侧（HTTP 入口）用本方法显式标注，
     * 使 logback pattern 的 {@code %X{bizNo}} 输出可被 {@code trace-grep.sh --bizno} 检索。</p>
     */
    public static void runWithBizNo(String bizNo, Runnable action) {
        String previous = MDC.get(BIZ_NO_KEY);
        if (bizNo != null && !bizNo.isBlank()) {
            MDC.put(BIZ_NO_KEY, bizNo);
        }
        try {
            action.run();
        } finally {
            if (previous != null) {
                MDC.put(BIZ_NO_KEY, previous);
            } else {
                MDC.remove(BIZ_NO_KEY);
            }
        }
    }
}
