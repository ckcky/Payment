package com.payment.catalog.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SKU 读缓存开关（014，cache-aside）。
 * 通过 {@code catalog.cache.enabled=false} 可整体关闭缓存（演示/调试时直连 DB）。
 */
@ConfigurationProperties("catalog.cache")
public class CatalogCacheProperties {

    private boolean enabled = true;
    private long ttlSeconds = 300;
    private String keyPrefix = "sku:";

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

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
