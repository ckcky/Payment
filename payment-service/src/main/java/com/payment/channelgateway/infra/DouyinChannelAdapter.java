package com.payment.channelgateway.infra;

import com.payment.channelgateway.application.spi.ChannelPluginDescriptor;
import com.payment.common.dto.channel.PaymentScene;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 抖音支付渠道 Adapter（Feature 028 / FR-010，ADR-0072）。
 *
 * <p>本类只声明「<b>身份 + 差异</b>」：身份 {@code DOUYIN}，差异是可独立配置的 mock 场景。
 * 所有横切行为均由 {@link AbstractMockChannelAdapter} 承载，<b>不得覆写</b>；
 * spec 037 / T6 起它同时是渠道插件（FR-014）。</p>
 *
 * <p><b>默认 {@code enabled=false}（FR-031）</b>：自动选路不会挑到本渠道；
 * 但显式指定仍按其执行（FR-020②/L3：{@code enabled} 语义是「别自动挑我」，不是「禁止使用」）。</p>
 */
@Component
public class DouyinChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "DOUYIN";

    /** 纯 mock 渠道的全部场景都是本地模拟，故声明全支持。 */
    private static final Set<PaymentScene> SUPPORTED_SCENES = Set.of(PaymentScene.values());

    /** 插件自描述：无回调路径（结果由进程内推送送达，与 MOCK 同族）。 */
    private static final ChannelPluginDescriptor DESCRIPTOR =
            ChannelPluginDescriptor.withoutCallback(CODE, "抖音支付（本地模拟）", SUPPORTED_SCENES, false);

    @Override
    public ChannelPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    /**
     * Spring 主构造：场景优先读 {@code payment.channel.adapters.DOUYIN.scenario}，
     * 未配则回落全局 {@code payment.channel.mock-scenario}（FR-014）。
     */
    @Autowired
    public DouyinChannelAdapter(
            @Value("${payment.channel.adapters.DOUYIN.scenario:${payment.channel.mock-scenario:SUCCESS}}") String scenario,
            @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
            @Value("${payment.channel.refund-async:true}") boolean refundAsync,
            @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /** 便捷构造（测试/演示脚本显式指定形态）。 */
    public DouyinChannelAdapter(Scenario scenario) {
        super(scenario, 1500L, false, 1000L);
    }
}
