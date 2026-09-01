package com.payment.mockchannel.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mock-channel-web 配置。
 *
 * <p>{@code secret} 与 payment-service 共用同一环境变量 {@code PAYMENT_CHANNEL_SECRET}：
 * 转发回调时用同一密钥签名。注意：当前渠道回调验签为 ADR-0025 占位空实现（payment 侧恒放行），
 * 故 payment 侧<b>不校验</b>该签名——「伪造签名」(signMode=FORGED) 在当前形态下<b>不会被拒</b>。
 * secret 仅作为将来接入真实验签（ADR-0052，见 docs/adr/0013，当前 ⛔ Not Implemented）时两侧对齐之用。</p>
 */
@ConfigurationProperties(prefix = "mock-channel")
public class MockChannelProperties {

    /** 渠道回调签名密钥（与 payment-service 共用同一 PAYMENT_CHANNEL_SECRET）。 */
    private String secret = "";

    /** 各服务 base-url，键为 /proxy/{service}/** 的服务段。 */
    private Map<String, String> services = new HashMap<>();

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }

    /** 取目标服务 base-url；未知服务段返回 null（由代理层转 404）。 */
    public String serviceUrl(String name) {
        return services.get(name);
    }
}
