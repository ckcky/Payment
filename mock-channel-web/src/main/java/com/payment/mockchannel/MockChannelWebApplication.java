package com.payment.mockchannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Mock 渠道收银台 + 演示控制台（ADR-0048 修订版）。
 *
 * <p>端口 8091。纯 HTTP 转发 + 静态页：不持有业务状态、不做资金操作、不依赖任何服务模块。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockChannelWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockChannelWebApplication.class, args);
    }
}
