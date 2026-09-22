package com.payment.testinfra;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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

    /**
     * 高基数运行期遍历断言（spec 035 §7.1 HC-1/HC-2/HC-4 的 registry 侧机制，ADR-0083 决策 2）：
     * 遍历 {@code MeterRegistry} 已注册 meters，断言
     * ① 每个 tag 的<b>键</b>属于登记的有界白名单；
     * ② 每个 tag 的<b>值</b>不含单号/ID/UUID 形态（长度 ≤64 且非 UUID/长数字形态——运行期无法
     *    穷举值域，用形态启发 + 白名单键双重拦截；权威值域守护在静态扫描 {@code MetricsCardinalityTest}）；
     * ③ 同名 meter 的序列数 ≤ 200（HC-4 预算）。
     *
     * <p>用途：各服务 L2a（{@code @SpringBootTest} + H2）用例驱动一轮真实业务后调用，
     * 断言「这一轮产生的所有标签」合规——是 035-A 交付的可复用断言本体。</p>
     */
    public static void assertTagValuesWithinAllowedSet(MeterRegistry registry, Set<String> allowedTagKeys) {
        Pattern uuidish = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}|\\d{12,}");
        Map<String, Integer> seriesByName = new HashMap<>();
        for (Meter meter : registry.getMeters()) {
            seriesByName.merge(meter.getId().getName(), 1, Integer::sum);
            for (Tag tag : meter.getId().getTags()) {
                Assertions.assertThat(allowedTagKeys)
                        .as("meter %s 的标签键 %s 须在有界白名单内（HC-1/HC-3）",
                                meter.getId().getName(), tag.getKey())
                        .contains(tag.getKey());
                Assertions.assertThat(tag.getValue())
                        .as("meter %s 标签 %s=%s 的值不得呈单号/ID 形态（HC-2：长度须 ≤64）",
                                meter.getId().getName(), tag.getKey(), tag.getValue())
                        .hasSizeLessThanOrEqualTo(64);
                Assertions.assertThat(uuidish.matcher(tag.getValue()).find())
                        .as("meter %s 标签 %s=%s 的值不得含 UUID 片段或长数字（HC-2）",
                                meter.getId().getName(), tag.getKey(), tag.getValue())
                        .isFalse();
            }
        }
        seriesByName.forEach((name, count) -> Assertions.assertThat(count)
                .as("指标 %s 的序列数须 ≤ HC-4 预算 200", name)
                .isLessThanOrEqualTo(200));
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
