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
 *
 * <p><b>baseUrl 是「客户端可达地址」，不是服务端调用目标。</b>{@code buildPayUrl} 把它
 * 拼进响应体，由<b>浏览器</b>打开（前端 {@code window.open(payUrl)}）。因此**不能**填
 * 容器内服务名：容器模式下填 {@code http://mock-channel-web:8091} 时，浏览器所在宿主
 * 解析不了该名字（NXDOMAIN），表现为「下单成功但收银台跳转不了」。容器模式应填
 * {@code http://localhost:8091}（端口已 publish 到宿主）。</p>
 *
 * <p>注意 {@code host.docker.internal} <b>也不是</b>可用替代——该名仅在容器内可解析，
 * 宿主同样 NXDOMAIN（2026-09-15 实测）。判据始终是「浏览器所在宿主能否打开」。</p>
 */
@ConfigurationProperties(prefix = "payment.mock-cashier")
public class MockCashierProperties {

    /** 是否启用收银台跳转路径（默认 false，不改既有行为）。 */
    private boolean enabled = false;

    /** 收银台页所在基地址（**浏览器可达**地址，非服务端调用目标；见类注释）。 */
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
