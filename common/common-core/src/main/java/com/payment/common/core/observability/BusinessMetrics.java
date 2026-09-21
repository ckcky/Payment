package com.payment.common.core.observability;

import java.time.Duration;

/**
 * 业务指标基座（plan T015）：抽象计数与耗时记录，具体 Micrometer 实现见 Phase 6（T070）。
 *
 * <p>业务指标必须反映真实业务事实（支付成功/失败/UNKNOWN、重复回调、退款、履约失败、
 * 权益发放失败、对账差异、结算失败等），不允许把「未确认」当成「成功/失败」计数。</p>
 */
public interface BusinessMetrics {

    /** 计数加 {@code value}（通常 1），带维度标签（如模块、状态）。 */
    void counter(String name, double value, String... tags);

    /** 记录一次耗时（如 UNKNOWN 收敛时长）。 */
    void timer(String name, Duration duration, String... tags);

    /**
     * 注册一个<b>采样型</b> gauge（spec 034 / T9）：每次抓取时回调 {@code valueSupplier}
     * 取当前值（如台账 PENDING 行数、DLQ 长度），而非累加计数。
     *
     * <p>回调在指标抓取线程执行，MUST 低成本（一次 COUNT / XLEN）；同名同标签重复注册
     * 由底层实现幂等吸收（Micrometer 同 id 仪表只保留一个）。default 空实现 =
     * {@link NoopBusinessMetrics} 等无注册表上下文静默丢弃，调用方无需判空。</p>
     */
    default void gauge(String name, java.util.function.Supplier<Number> valueSupplier, String... tags) {
        // 无 MeterRegistry 上下文时不落地（no-op）。
    }
}
