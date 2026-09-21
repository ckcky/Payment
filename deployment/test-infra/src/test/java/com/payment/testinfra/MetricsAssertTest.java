package com.payment.testinfra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MetricsAssert 单测（纯 JVM，SimpleMeterRegistry）：机制自证，指标名全部中性（C-5）。
 */
class MetricsAssertTest {

    @Test
    @DisplayName("counter 读取与下限断言（含标签对）")
    void counterValueAndAssert() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("probe.events.total", "kind", "alpha").increment(3.0);

        assertThat(MetricsAssert.counterValue(registry, "probe.events.total", "kind", "alpha")).isEqualTo(3.0);
        assertThat(MetricsAssert.counterValue(registry, "probe.events.total", "kind", "beta"))
                .as("不同标签值是不同序列").isEqualTo(0.0);
        assertThat(MetricsAssert.counterValue(registry, "probe.never.recorded"))
                .as("未注册 counter 视为 0（从未发生 == 0 次）").isEqualTo(0.0);

        MetricsAssert.assertCounterAtLeast(registry, "probe.events.total", 3.0, "kind", "alpha");
    }

    @Test
    @DisplayName("timer 次数/累计耗时读取与断言")
    void timerCountAndTotal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.timer("probe.stage", "result", "ok").record(java.time.Duration.ofMillis(250));

        assertThat(MetricsAssert.timerCount(registry, "probe.stage", "result", "ok")).isEqualTo(1L);
        assertThat(MetricsAssert.timerCount(registry, "probe.stage", "result", "fail")).isEqualTo(0L);
        assertThat(MetricsAssert.timerTotalMillis(registry, "probe.stage", "result", "ok"))
                .isGreaterThanOrEqualTo(250.0);

        MetricsAssert.assertTimerCountAtLeast(registry, "probe.stage", 1L, "result", "ok");
    }

    @Test
    @DisplayName("标签必须成对出现（构造错误 fail fast）")
    void oddTagPairsRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> MetricsAssert.counterValue(registry, "probe.x", "lonely"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("高基数遍历断言：合规 registry 全绿（spec 035 §7.1 机制自证·阳性）")
    void allowedTagsPass() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("probe.events.total", "module", "payment", "result", "ok").increment();
        MetricsAssert.assertTagValuesWithinAllowedSet(registry, java.util.Set.of("module", "result"));
    }

    @Test
    @DisplayName("高基数遍历断言：越白名单键 / 单号形态值 / 超预算序列各拦一次（阴性对照）")
    void highCardinalityRejected() {
        java.util.Set<String> allowed = java.util.Set.of("module");
        SimpleMeterRegistry keyViolator = new SimpleMeterRegistry();
        keyViolator.counter("probe.x", "paymentNo", "PM0001").increment();
        assertThatThrownBy(() -> MetricsAssert.assertTagValuesWithinAllowedSet(keyViolator, allowed))
                .as("键不在白名单即红").isInstanceOf(AssertionError.class);

        SimpleMeterRegistry valueViolator = new SimpleMeterRegistry();
        valueViolator.counter("probe.y", "module", "PM20260921123456789").increment();
        assertThatThrownBy(() -> MetricsAssert.assertTagValuesWithinAllowedSet(valueViolator, allowed))
                .as("值呈长数字单号形态即红").isInstanceOf(AssertionError.class);

        SimpleMeterRegistry seriesViolator = new SimpleMeterRegistry();
        for (int i = 0; i < 201; i++) {
            seriesViolator.counter("probe.z", "module", "m" + i).increment();
        }
        assertThatThrownBy(() -> MetricsAssert.assertTagValuesWithinAllowedSet(seriesViolator,
                java.util.Set.of("module")))
                .as("单指标序列数超 HC-4 预算 200 即红").isInstanceOf(AssertionError.class);
    }
}
