package com.payment.order.web;

import com.payment.order.application.OrderTimeoutProperties;
import com.payment.order.application.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用定时任务（订单超时扫描）并注册配置属性绑定。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({OrderTimeoutProperties.class, RateLimitProperties.class})
public class SchedulerConfig {
}
