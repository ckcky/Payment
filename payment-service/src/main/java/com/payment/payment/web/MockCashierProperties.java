package com.payment.payment.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock 收银台开关（ADR-0048 修订版，2026-08-31 负责人裁决）。
 *
 * <p>{@code enabled=false}（默认）：支付创建保持既有"同步 charge"主链，零行为变化，
 * 既有测试零影响。</p>
 *
 * <p>{@code enabled=true}（演示环境由 start-all.sh 开启）：{@code createPayment}
 * 跳过渠道内联同步调用，Payment 停留 PROCESSING 等待收银台回调驱动状态迁移；
 * 回调迟迟不来则由既有 TimeoutScanner（30s）转 UNKNOWN、ChannelQueryScheduler
 * 主动查询收敛 —— 正好演示「点了不回调」与「不猜成败落账」。响应附带
 * {@code payUrl} 指向 mock-channel-web 收银台页。</p>
 */
@ConfigurationProperties(prefix = "payment.mock-cashier")
public class MockCashierProperties {

    /** 是否启用收银台跳转路径（默认 false，不改既有行为）。 */
    private boolean enabled = false;

    /** mock-channel-web 基地址（收银台页所在）。 */
    private String baseUrl = "http://localhost:8091";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
