package com.payment.payment.infra.channel;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 支付宝渠道 Adapter（Feature 028 / FR-010，ADR-0072）。
 *
 * <p>本类只声明「<b>身份 + 差异</b>」：身份 {@code ALIPAY}，差异是可独立配置的 mock 场景。
 * 所有横切行为（金额尾数注入 / 退款异步推送 / 每实例独立 {@code runId} / 严格枚举解析）
 * 均由 {@link AbstractMockChannelAdapter} 承载，<b>不得覆写</b>。</p>
 *
 * <p><b>已知限制（spec 028 L5）</b>：本类在<b>协议层</b>仍是同一 mock 语义——结构与身份变真，
 * 但接真实渠道 SDK 是独立议题。</p>
 */
@Component
public class AlipayChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "ALIPAY";

    @Override
    public String channelCode() {
        return CODE;
    }

    /**
     * Spring 主构造：场景优先读 {@code payment.channel.adapters.ALIPAY.scenario}，
     * 未配则回落全局 {@code payment.channel.mock-scenario}（FR-014）。
     */
    @Autowired
    public AlipayChannelAdapter(
            @Value("${payment.channel.adapters.ALIPAY.scenario:${payment.channel.mock-scenario:SUCCESS}}") String scenario,
            @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
            @Value("${payment.channel.refund-async:true}") boolean refundAsync,
            @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /** 便捷构造（测试/演示脚本显式指定形态）。 */
    public AlipayChannelAdapter(Scenario scenario) {
        super(scenario, 1500L, false, 1000L);
    }
}
