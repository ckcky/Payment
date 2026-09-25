package com.payment.channelgateway.infra;

import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.common.dto.channel.PaymentScene;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Mock 渠道（T035 契约的默认实现 / 兼容渠道，FR-011）：
 * 身份为 {@code MOCK}，用于「未指定渠道的旧脚本」与既有约 30 个测试文件。
 *
 * <p><b>本类是 {@link AbstractMockChannelAdapter} 的薄子类</b>——自 Feature 028 起，全部行为
 * （金额尾数确定性故障注入、退款「受理 + 异步推送」、每实例独立 {@code runId}、
 * {@code mock-scenario} 严格枚举解析）已上移到基类；spec 037 / T6 又把基类整体接到
 * {@code AbstractChannelPlugin} 上，故本类<b>同时是渠道插件</b>（FR-014），
 * 需要自己提供的只剩：① 身份 {@code MOCK}；② 插件自描述；③ <b>全部 6 个既有构造签名</b>
 * （FR-011/FR-036/SC-012）。</p>
 *
 * <p>保留构造签名的目的很实际：10 个 {@code new MockChannelAdapter(...)} 的测试文件与未指定渠道的
 * 旧脚本<b>零改动</b>——结构重构不该把成本转嫁给调用方。</p>
 *
 * <p>渠道 {@link AlipayChannelAdapter} / {@link DouyinChannelAdapter}
 * 与本类行为完全一致（FR-013），差别只在身份、自描述与各自可配的 scenario。
 * （{@code WECHAT} 自 spec 039 起改按插件范式接入，见
 * {@code com.payment.channelgateway.infra.wechat.WechatChannelPlugin}。）</p>
 */
@Component
public class MockChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "MOCK";

    /** 纯 mock 渠道的全部场景都是本地模拟，故声明全支持（与迁移前接口默认值同一口径）。 */
    private static final Set<PaymentScene> SUPPORTED_SCENES = Set.of(PaymentScene.values());

    /**
     * 插件自描述：<b>无回调路径</b>。
     *
     * <p>mock 渠道的退款结果由进程内推送（{@code PaymentNotifyPort}）送达，
     * 不存在 HTTP 回调端点——声明一个回调路径会让通用回调端点误以为可以收单，
     * 而那个端点永远不会有人调用。</p>
     */
    private static final ChannelPluginDescriptor DESCRIPTOR =
            ChannelPluginDescriptor.withoutCallback(CODE, "Mock 渠道（本地模拟）", SUPPORTED_SCENES, false);

    @Override
    public ChannelPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    /** 兼容构造①：默认 SUCCESS 场景。 */
    public MockChannelAdapter() {
        this(Scenario.SUCCESS);
    }

    /** 兼容构造②：显式场景。 */
    public MockChannelAdapter(Scenario scenario) {
        this(scenario, 1500L);
    }

    /** 兼容构造③：场景 + HTTP 超时预算。 */
    public MockChannelAdapter(Scenario scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs) {
        this(scenario, httpTimeoutMs, false, 1000L);
    }

    /** 兼容构造④：场景名 + 超时（测试/演示脚本按场景名构建，同步模式）。 */
    public MockChannelAdapter(String scenario, long httpTimeoutMs) {
        super(scenario, httpTimeoutMs);
    }

    /** 兼容构造⑤：全参（测试 / 演示脚本显式指定形态）。 */
    public MockChannelAdapter(Scenario scenario, long httpTimeoutMs,
                              boolean refundAsync, long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /**
     * Spring 主构造⑥（ADR-0049 + spec 019 / D7）：场景由 {@code payment.channel.mock-scenario} 决定
     * （默认 {@code SUCCESS}）；退款异步模式由 {@code payment.channel.refund-async} 决定（默认开）。
     *
     * <p><b>取值必须严格等于 {@link Scenario} 枚举名</b>（大写下划线）。不做别名、不做大小写容错：
     * 非法值直接让 Bean 创建失败并给出合法取值清单（ADR-0049 第 2 条）。</p>
     */
    @Autowired
    public MockChannelAdapter(@Value("${payment.channel.mock-scenario:SUCCESS}") String scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
                              @Value("${payment.channel.refund-async:true}") boolean refundAsync,
                              @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }
}
