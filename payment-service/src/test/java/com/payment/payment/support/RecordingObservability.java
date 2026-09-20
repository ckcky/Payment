package com.payment.payment.support;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录式可观测性替身（spec 030 / Phase 10）。
 *
 * <p>校验失败的「三件套」（FR-213：不推进 + 计指标 + 写审计）里，后两件在
 * {@code NoopBusinessMetrics} + 真日志下**看不见**——测试只能断言状态没动，
 * 无法证明「痕迹留了」。这里把两类观测都收进内存，让断言可以同时锁住三件套。</p>
 *
 * <p>不是 mock 框架的 spy：用最朴素的方式记录 name/tags 与 audit 参数，
 * 避免测试对实现细节的过度耦合（只关心「有没有记」「记在哪一维」）。</p>
 */
public final class RecordingObservability {

    /** 指标调用：名字 + 标签对（偶数长度，形如 key1,value1,key2,value2）。 */
    public record CounterCall(String name, double value, List<String> tags) {

        /** 取某标签值；不存在返回 {@code null}。 */
        public String tag(String key) {
            for (int i = 0; i + 1 < tags.size(); i += 2) {
                if (key.equals(tags.get(i))) {
                    return tags.get(i + 1);
                }
            }
            return null;
        }
    }

    /** 审计调用：对齐 {@link StructuredAuditLogger#audit} 的参数序。 */
    public record AuditCall(String action, String idempotencyKey, Long amountMinor, String currencyCode,
                            String fromStatus, String toStatus, String entityType, String entityId) {
    }

    private final List<CounterCall> counters = new ArrayList<>();
    private final List<AuditCall> audits = new ArrayList<>();

    public final BusinessMetrics metrics = new BusinessMetrics() {
        @Override
        public void counter(String name, double value, String... tags) {
            counters.add(new CounterCall(name, value, List.of(tags)));
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
            // 校验路径不打点；如需可在此扩展
        }
    };

    public final StructuredAuditLogger audit = new StructuredAuditLogger() {
        @Override
        public void audit(String action, String idempotencyKey, Long amountMinor, String currencyCode,
                          String fromStatus, String toStatus, String entityType, String entityId) {
            audits.add(new AuditCall(action, idempotencyKey, amountMinor, currencyCode,
                    fromStatus, toStatus, entityType, entityId));
        }
    };

    /** 指定名字的指标总调用次数（所有维度合计）。 */
    public long countOf(String name) {
        return counters.stream().filter(c -> name.equals(c.name())).count();
    }

    /** 指定名字 + 某维标签值的调用次数。例：{@code countOf("payment.notify_rejected", "reason", "amount")}。 */
    public long countOf(String name, String tagKey, String tagValue) {
        return counters.stream()
                .filter(c -> name.equals(c.name()) && tagValue.equals(c.tag(tagKey)))
                .count();
    }

    public List<CounterCall> counters() {
        return List.copyOf(counters);
    }

    public List<AuditCall> audits() {
        return List.copyOf(audits);
    }

    /** 是否记录过某动作的审计。 */
    public boolean auditedAction(String action) {
        return audits.stream().anyMatch(a -> action.equals(a.action()));
    }

    @Override
    public String toString() {
        return "RecordingObservability{counters=" + counters + ", audits=" + audits + '}';
    }

    // 便于断言失败时打印标签对的工具（测试内用）
    static Map<String, String> tagsOf(CounterCall call) {
        Map<String, String> map = new LinkedHashMap<>();
        List<String> tags = call.tags();
        for (int i = 0; i + 1 < tags.size(); i += 2) {
            map.put(tags.get(i), tags.get(i + 1));
        }
        return map;
    }
}
