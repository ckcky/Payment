package com.payment.order.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 订单超时配置（013/014）：基于 Redis ZSet 的时间轮。
 * score = 到期时间戳（毫秒），member = orderId；调度器定期扫描到期项并取消。
 */
@ConfigurationProperties(prefix = "order.timeout")
public class OrderTimeoutProperties {

    /** 是否启用超时取消（默认启用）。 */
    private boolean enabled = true;
    /** 订单待支付超时时长（秒），到点仍未支付则取消并释放库存。 */
    private long ttlSeconds = 900;
    /** 扫描周期（毫秒）。 */
    private long pollMillis = 5000;
    /** ZSet key。 */
    private String zsetKey = "order:timeouts";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    public String getZsetKey() {
        return zsetKey;
    }

    public void setZsetKey(String zsetKey) {
        this.zsetKey = zsetKey;
    }
}
