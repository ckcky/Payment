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
}
