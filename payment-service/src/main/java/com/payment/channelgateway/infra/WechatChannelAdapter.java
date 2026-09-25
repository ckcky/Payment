package com.payment.channelgateway.infra;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 微信支付渠道 Adapter（Feature 028 / FR-010，ADR-0072）。
 *
 * <p>本类只声明「<b>身份 + 差异</b>」：身份 {@code WECHAT}，差异是可独立配置的 mock 场景。
 * 所有横切行为均由 {@link AbstractMockChannelAdapter} 承载，<b>不得覆写</b>。</p>
 *
 * <p><b>与 INV-6 的关系</b>：一旦某支付单的 {@code payment_attempts.channel_code = WECHAT}，
 * 其退款/重试/主动查询必须回到本 Adapter——不得因 ALIPAY 优先级更高而改道。</p>
 */
@Component
public class WechatChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "WECHAT";

    @Override
    public String channelCode() {
        return CODE;
    }

    /**
     * Spring 主构造：场景优先读 {@code payment.channel.adapters.WECHAT.scenario}，
     * 未配则回落全局 {@code payment.channel.mock-scenario}（FR-014）。
     */
    @Autowired
    public WechatChannelAdapter(
            @Value("${payment.channel.adapters.WECHAT.scenario:${payment.channel.mock-scenario:SUCCESS}}") String scenario,
            @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
            @Value("${payment.channel.refund-async:true}") boolean refundAsync,
            @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /** 便捷构造（测试/演示脚本显式指定形态）。 */
    public WechatChannelAdapter(Scenario scenario) {
        super(scenario, 1500L, false, 1000L);
    }
}
