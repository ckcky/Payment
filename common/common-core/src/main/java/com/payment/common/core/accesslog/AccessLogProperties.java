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
 * @param excludePaths 排除路径（Ant 模式，默认 {@code ["/actuator/**"]}，D4）
 */
@ConfigurationProperties("common.access-log")
public record AccessLogProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4096") int maxBodyBytes,
        @DefaultValue("/actuator/**") List<String> excludePaths) {

    public AccessLogProperties {
        if (excludePaths == null) {
            excludePaths = List.of("/actuator/**");
        } else {
            excludePaths = List.copyOf(excludePaths);
        }
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 4096;
        }
    }
}
