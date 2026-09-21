package com.payment.common.core.accesslog;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 访问日志配置（spec 021 / FR-001，ADR-0068）：绑定 {@code common.access-log.*}。
 *
 * <p>项目首个属性类，走 Boot 3 推荐的 record 构造器绑定；未配置时取下列默认值。</p>
 *
 * @param enabled      总开关（默认 true；false 时整个 AccessLogFilter 不装配，NFR-001）
 * @param maxBodyBytes 报文截断阈值（默认 4096 字节；超出截断并带省略标记，D4）
 * @param excludePaths 排除路径（Ant 模式；spec 035 §10 C-1 / ADR-0083 决策 3 起默认
 *                     {@code ["/actuator/**", "/internal/channels/**"]}——**渠道回调入口一律不落正文**，
 *                     支付宝 notify 的 form（含 {@code sign}/{@code out_trade_no}/{@code total_amount}）
 *                     不得进 ACCESS_LOG；密钥/完整报文不入日志靠「排除 + 显式禁令 + 测试」，
 *                     不启用通用脱敏（ADR-0027 裁决不变，字段级 mask 走服务侧 Bean 覆盖点））
 */
@ConfigurationProperties("common.access-log")
public record AccessLogProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4096") int maxBodyBytes,
        @DefaultValue({"/actuator/**", "/internal/channels/**"}) List<String> excludePaths) {

    public AccessLogProperties {
        if (excludePaths == null) {
            excludePaths = List.of("/actuator/**", "/internal/channels/**");
        } else {
            excludePaths = List.copyOf(excludePaths);
        }
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 4096;
        }
    }
}
