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

    // ---- spec 029 / FR-605、FR-606、SC-11：bizNo 维度 ----

    /** bizNo 在块内可见、块后清理（否则线程复用会串单号，日志张冠李戴）。 */
    @Test
    void runWithBizNoSetsAndCleansUp() {
        assertThat(MDC.get(TraceContext.BIZ_NO_KEY)).isNull();
        AtomicReference<String> inside = new AtomicReference<>();
        TraceContext.runWithBizNo("ORDER-1", () -> inside.set(MDC.get(TraceContext.BIZ_NO_KEY)));
        assertThat(inside.get()).isEqualTo("ORDER-1");
        assertThat(MDC.get(TraceContext.BIZ_NO_KEY)).isNull();
    }

    /** 嵌套时恢复外层 bizNo；异常路径同样清理。 */
    @Test
    void runWithBizNoRestoresOuterAndCleansOnFailure() {
        TraceContext.runWithBizNo("ORDER-OUTER", () -> {
            TraceContext.runWithBizNo("PAY-INNER", () ->
                    assertThat(MDC.get(TraceContext.BIZ_NO_KEY)).isEqualTo("PAY-INNER"));
            // 内层退出后回到外层
            assertThat(MDC.get(TraceContext.BIZ_NO_KEY)).isEqualTo("ORDER-OUTER");

            try {
                TraceContext.runWithBizNo("REFUND", () -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // 异常仍恢复外层
            }
            assertThat(MDC.get(TraceContext.BIZ_NO_KEY)).isEqualTo("ORDER-OUTER");
        });
        assertThat(MDC.get(TraceContext.BIZ_NO_KEY)).isNull();
    }

    /**
     * SC-11 核心语义：bizNo 与 traceId 是两个正交维度——
     * 同一 bizNo 下可以出现多个 traceId（同一订单被查三次 = 三个 traceId），
     * 而 bizNo 的进出不影响 traceId 的值。
     *
     * <p>这条断言是「按 bizNo 检索能捞出全历史」的单元级依据：
     * 若实现把 bizNo 与 traceId 绑定（进入新 bizNo 就重置 traceId），
     * 同一订单的多次请求会被切成互不相干的链路，检索维度就塌成一个。</p>
     *
     * <p>注意：{@code runWithBizNo} <b>只</b>管理 bizNo，不碰 traceId——
     * 因此这里用「块内设定 traceId 后在块外仍可见（未被 bizNo 作用域重置）」来证明正交性。</p>
     */
    @Test
    void bizNoAndTraceIdAreOrthogonalDimensions() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-fixed");
        try {
            // 同一 bizNo，两次不同 traceId（模拟同一订单的两次请求）
            AtomicReference<String> t1 = new AtomicReference<>();
            TraceContext.runWithBizNo("ORDER-9", () -> {
                MDC.put(TraceIdFilter.MDC_KEY, "trace-a");
                t1.set(MDC.get(TraceIdFilter.MDC_KEY));
            });
            AtomicReference<String> t2 = new AtomicReference<>();
            TraceContext.runWithBizNo("ORDER-9", () -> {
                MDC.put(TraceIdFilter.MDC_KEY, "trace-b");
                t2.set(MDC.get(TraceIdFilter.MDC_KEY));
            });
            assertThat(t1.get()).as("第一次请求的 traceId").isEqualTo("trace-a");
            assertThat(t2.get()).as("第二次请求的 traceId").isEqualTo("trace-b");
            assertThat(t1.get()).as("同一 bizNo 下允许多个 traceId（正交，非绑定）")
                    .isNotEqualTo(t2.get());

            // bizNo 作用域进出不影响 traceId 维度：块内最终值在块外依然可见
            assertThat(MDC.get(TraceIdFilter.MDC_KEY))
                    .as("runWithBizNo 不管理/不重置 traceId（正交性）")
                    .isEqualTo("trace-b");
            // 而 bizNo 自身在被清理（作用域语义）
            assertThat(MDC.get(TraceContext.BIZ_NO_KEY))
                    .as("bizNo 出作用域即清理").isNull();
        } finally {
            MDC.clear();
        }
    }
}
