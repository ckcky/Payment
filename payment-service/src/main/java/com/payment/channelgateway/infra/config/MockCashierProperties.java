package com.payment.channelgateway.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock 收银台开关（ADR-0048 修订版，2026-08-31 负责人裁决）。
 *
 * <p>{@code enabled=false}（默认）：支付创建保持既有"同步 charge"主链，零行为变化，
 * 既有测试零影响。</p>
 *
 * <p>{@code enabled=true}（演示环境由 start-all.sh 开启）：扣款被渠道网关在派发前截住，
 * 直接返回一个指向收银台的付款凭证，Payment 停留 PROCESSING 等待收银台回调驱动状态迁移；
 * 回调迟迟不来则由既有 TimeoutScanner（30s）转 UNKNOWN、ChannelQueryScheduler
 * 主动查询收敛 —— 正好演示「点了不回调」与「不猜成败落账」。</p>
 *
 * <h3>spec 041：本类从 {@code com.payment.payment.web} 迁入渠道网关域</h3>
 * <p>它描述的是「渠道扣款要不要走演示收银台」，<b>是渠道域的知识</b>。
 * 改造前它放在 Payment 的 web 包下、由 {@code PaymentController} 读取并据此决定是否调渠道，
 * 等于让资金动作域（而且是 Controller）替渠道域做裁决。迁入后由
 * {@link DemoCashierDispatchPolicy} 消费，Payment 侧不再出现本类型。</p>
 *
 * <p><b>baseUrl 是「客户端可达地址」，不是服务端调用目标。</b>它被拼进凭证 payload，
 * 由<b>浏览器</b>打开（前端 {@code window.open(payUrl)}）。因此**不能**填
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
