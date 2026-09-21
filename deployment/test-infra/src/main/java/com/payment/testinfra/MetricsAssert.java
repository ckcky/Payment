package com.payment.testinfra;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;

/**
 * 指标断言工具（spec 033 §5.2 O-1「MetricsAssert」/ §4.2 B-4，供 035 的指标用例本体复用；
 * 基座只提供读取与断言机制，不定义任何业务指标名）。
 *
 * <p>用法（035 侧）：</p>
 * <pre>{@code
 * MetricsAssert.assertCounterAtLeast(meterRegistry, "payment.abandoned.total", 1);
 * MetricsAssert.assertTimerCountAtLeast(meterRegistry, "reconcile.match", 1, "result", "MATCHED");
 * }</pre>
 */
public final class MetricsAssert {

    private MetricsAssert() {
    }

    /** 读 counter 当前值（无该 counter 视为 0——「从未发生」与「发生 0 次」在断言上等价）。 */
    public static double counterValue(MeterRegistry registry, String name, String... tagKeyValuePairs) {
        return registry.counter(name, tags(tagKeyValuePairs)).count();
    }

    /** 读 timer 累计次数（无该 timer 视为 0）。 */
    public static long timerCount(MeterRegistry registry, String name, String... tagKeyValuePairs) {
        Timer timer = registry.find(name).tags(tags(tagKeyValuePairs)).timer();
        return timer == null ? 0L : timer.count();
    }

    /** 读 timer 累计耗时（毫秒；无该 timer 视为 0）。 */
    public static double timerTotalMillis(MeterRegistry registry, String name, String... tagKeyValuePairs) {
        Timer timer = registry.find(name).tags(tags(tagKeyValuePairs)).timer();
        return timer == null ? 0.0 : timer.totalTime(TimeUnit.MILLISECONDS);
    }

    /** 断言 counter ≥ expected（失败信息带指标名与标签，便于 035 的用例定位）。 */
    public static void assertCounterAtLeast(MeterRegistry registry, String name,
                                            double expected, String... tagKeyValuePairs) {
        double actual = counterValue(registry, name, tagKeyValuePairs);
        Assertions.assertThat(actual)
                .as("counter %s%s", name, tagKeyValuePairs.length == 0 ? "" : " " + describe(tagKeyValuePairs))
                .isGreaterThanOrEqualTo(expected);
    }

    /** 断言 timer 次数 ≥ expected。 */
    public static void assertTimerCountAtLeast(MeterRegistry registry, String name,
                                               long expected, String... tagKeyValuePairs) {
        long actual = timerCount(registry, name, tagKeyValuePairs);
        Assertions.assertThat(actual)
                .as("timer %s%s", name, tagKeyValuePairs.length == 0 ? "" : " " + describe(tagKeyValuePairs))
                .isGreaterThanOrEqualTo(expected);
    }

    private static Iterable<Tag> tags(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("标签必须是 key/value 成对出现，实际 " + keyValuePairs.length + " 个");
        }
        java.util.List<Tag> result = new java.util.ArrayList<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            result.add(Tag.of(keyValuePairs[i], keyValuePairs[i + 1]));
        }
        return result;
    }

    private static String describe(String... keyValuePairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(keyValuePairs[i]).append("=").append(keyValuePairs[i + 1]);
        }
        return sb.append("}").toString();
    }
}
