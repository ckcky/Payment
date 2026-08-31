package com.payment.order.application.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下单入口限流配置（014）：固定窗口，超限 429 快速失败。
 */
@ConfigurationProperties(prefix = "order.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
    /** 窗口内容量（默认每窗口 50 次下单请求）。 */
    private int capacity = 50;
    /** 窗口长度（毫秒）。 */
    private long windowMillis = 1000;
    /** 限流桶标识（默认按 /orders 端点）。 */
    private String bucket = "/orders";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
