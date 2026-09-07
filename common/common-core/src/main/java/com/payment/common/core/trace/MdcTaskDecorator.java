package com.payment.common.core.trace;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * MDC 任务装饰器（spec 021 / FR-005，D7）：提交任务时捕获提交线程的 MDC（traceId 等），
 * 在执行线程恢复，执行完毕清理——供 {@code ThreadPoolTaskExecutor.setTaskDecorator()} 使用
 * （直接实现 Spring 的 {@link TaskDecorator}）。
 *
 * <p>线程复用不串号（与 TraceIdFilter 的 finally 清理同一纪律）：执行线程用后必清，
 * 不依赖任务方自觉。</p>
 *
 * <p>注意：仅传播 MDC；{@link TraceContext} 的 ThreadLocal 随业务入口
 * {@code TraceContext.runWithNewTrace} / Filter 自行管理，避免双重来源不一致。</p>
 */
public class MdcTaskDecorator implements org.springframework.core.task.TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            }
            try {
                runnable.run();
            } finally {
                // 恢复执行线程原上下文（线程池复用防串号），无则清空。
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
