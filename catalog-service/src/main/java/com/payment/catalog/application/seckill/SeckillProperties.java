package com.payment.catalog.application.seckill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀配额预扣开关（014）。
 * 关闭后 {@code tryPreDeduct} 直接放行（bypass），由 DB 三段式库存兜底。
 */
@ConfigurationProperties("catalog.seckill")
public class SeckillProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
