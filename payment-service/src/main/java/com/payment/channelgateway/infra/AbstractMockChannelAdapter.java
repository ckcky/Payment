package com.payment.channelgateway.infra;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.channelgateway.application.spi.AbstractChannelPlugin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Mock 渠道族的抽象基类（Feature 028 / FR-009，ADR-0072）。
 *
 * <p><b>spec 037 / T6 起本类是「兼容薄层」</b>：全部横切行为（金额尾数确定性故障注入、
 * {@code mock-scenario} 基线场景、退款「受理 + 异步推送」、每实例独立 {@code runId}、
 * 严格枚举解析）都已上移到 {@link AbstractChannelPlugin}——
 * 后者现在是<b>全部渠道唯一的基类</b>（FR-014）。</p>
 *
 * <h3>为什么保留这个类而不删掉</h3>
 * 三个 Adapter（{@link MockChannelAdapter} / {@link AlipayChannelAdapter} /
 * {@link DouyinChannelAdapter}）都以它为父类。若把它们改成直接继承
 * {@code AbstractChannelPlugin}、再各自重写一遍「尾数注入 + 退款异步推送」，
 * 得到的是<b>三份同源代码</b>——必然漂移，而 spec 022 的 E2E 断言恰恰依赖
 * 「全渠道同一金额触发同一结果」。
 *
 * <p>保留一个只做构造转发的薄层，换来的是：① 三个 Adapter <b>零改动</b>地
 * （间接）成为插件，回归面最小；② 「Mock 渠道族」这个语义分组仍有归属
 * （它们共享「本地模拟 + 进程内推送」这一族特征，与 Stripe / Wechat 这类
 * 真实渠道插件在概念上可分）。</p>
 *
 * <p><b>本类刻意不加 {@code @Component}</b>：抽象类不是 Bean，加注会带来「Bean 定义与
 * 实际实例不符」的歧义。渠道 Bean 由各具体子类提供。</p>
 *
 * @see AbstractChannelPlugin 模板方法基类（mock 语义的唯一实现处）
 * @see MockChannelAdapter 兼容用的 MOCK 渠道（保留全部既有构造签名）
 */
public abstract class AbstractMockChannelAdapter extends AbstractChannelPlugin {

    // ===================== Mock 渠道族的默认「无真实模式」语义 =====================

    /**
     * 默认<b>无真实模式</b>：mock 渠道族里真实模式是<b>例外</b>而非默认——
     * 目前只有 {@link AlipayChannelAdapter} 覆写为「按沙箱开关回答」（FR-131）。
     * 未覆写的渠道在染色 SANDBOX 时由基类门控硬失败（FR-241 / INV-8），
     * <b>绝不静默回落 mock</b>。
     */
    @Override
    protected boolean isRealModeEnabled() {
        return false;
    }

    /**
     * 三个真实模式原语：对「无真实模式」的渠道而言<b>不可达</b>——
     * 染色 SANDBOX 会先被 {@code requireRealModeIfSandboxRequested} 拦下并抛 400，
     * 未染色则走 {@code doMockXxx}。若真被执行，说明 {@link #isRealModeEnabled()}
     * 的实现有误，故<b>响亮失败</b>而不是静默返回一个假结果。
     */
    @Override
    protected ChannelResult doRealCharge(ChargeRequest request) {
        throw unreachable("charge");
    }

    @Override
    protected ChannelResult doRealRefund(RefundRequest request) {
        throw unreachable("refund");
    }

    @Override
    protected ChannelResult doRealQuery(QueryStatusRequest request) {
        throw unreachable("query");
    }

    private static UnsupportedOperationException unreachable(String operation) {
        return new UnsupportedOperationException(
                "channel has no real mode: doReal" + operation + " must never be reached "
                        + "(isRealModeEnabled() returns false, so the sandbox gate rejects first)");
    }

    // ===================== 构造转发 =====================

    /** 全参构造（场景对象形态）：转发给内核基类。 */
    protected AbstractMockChannelAdapter(Scenario scenario, long httpTimeoutMs,
                                         boolean refundAsync, long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /** 场景名 + 超时（测试/演示脚本按场景名构建，同步模式）。 */
    protected AbstractMockChannelAdapter(String scenario, long httpTimeoutMs) {
        super(scenario, httpTimeoutMs);
    }

    /**
     * Spring 装配构造：场景由 {@code payment.channel.mock-scenario} 决定、未配时回落
     * 全局默认（FR-014）；退款异步模式由 {@code payment.channel.refund-async} 决定（默认开）。
     *
     * <p><b>取值必须严格等于 {@link Scenario} 枚举名</b>（大写下划线）。不做别名、不做大小写容错：
     * 非法值直接让 Bean 创建失败并给出合法取值清单，避免「配错了却静默走默认成功」——
     * 那是最难排查的假绿（ADR-0049 第 2 条）。</p>
     */
    @Autowired
    protected AbstractMockChannelAdapter(@Value("${payment.channel.mock-scenario:SUCCESS}") String scenario,
                                         @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
                                         @Value("${payment.channel.refund-async:true}") boolean refundAsync,
                                         @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }
}
