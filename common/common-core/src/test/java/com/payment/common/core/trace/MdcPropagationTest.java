package com.payment.common.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * MDC 传播单测（spec 021 / T309）：MdcTaskDecorator 跨线程传播 + 用后清理；
 * TraceContext.runWithNewTrace 入口建号 + finally 清理 + 已有号复用（AC3.1/AC3.2）。
 */
class MdcPropagationTest {

    @Test
    void decoratorPropagatesMdcToWorkerAndCleansUpAfterwards() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-parent");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> seen = new AtomicReference<>();
            pool.submit(new MdcTaskDecorator().decorate(() ->
                    seen.set(MDC.get(TraceIdFilter.MDC_KEY)))).get(2, TimeUnit.SECONDS);
            assertThat(seen.get()).isEqualTo("trace-parent");

            // 线程复用不串号：第二个任务（未装饰）不应看到上个任务的 MDC 残留
            AtomicReference<String> leak = new AtomicReference<>("sentinel");
            pool.submit(() -> leak.set(MDC.get(TraceIdFilter.MDC_KEY))).get(2, TimeUnit.SECONDS);
            assertThat(leak.get()).isNull();
        } finally {
            pool.shutdownNow();
            MDC.clear();
        }
    }

    @Test
    void runWithNewTraceGeneratesTraceIdAndCleansUp() {
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        AtomicReference<String> inside = new AtomicReference<>();
        TraceContext.runWithNewTrace(() -> inside.set(MDC.get(TraceIdFilter.MDC_KEY)));
        assertThat(inside.get()).isNotBlank();
        // finally 清理：MDC 与 TraceContext 均无残留（AC3.2）
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void runWithNewTraceReusesExistingTraceIdAndRestoresIt() {
        TraceContext.setTraceId("existing-trace");
        MDC.put(TraceIdFilter.MDC_KEY, "existing-trace");
        try {
            AtomicReference<String> inside = new AtomicReference<>();
            TraceContext.runWithNewTrace(() -> inside.set(MDC.get(TraceIdFilter.MDC_KEY)));
            assertThat(inside.get()).isEqualTo("existing-trace");
            // 外层上下文不被破坏
            assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo("existing-trace");
            assertThat(TraceContext.getTraceId()).isEqualTo("existing-trace");
        } finally {
            MDC.clear();
            TraceContext.clear();
        }
    }

    @Test
    void runWithNewTraceCleansUpEvenOnFailure() {
        try {
            TraceContext.runWithNewTrace(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // 传播原异常
        }
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        assertThat(TraceContext.getTraceId()).isNull();
    }
}
