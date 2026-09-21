package com.payment.common.mq.dlq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DLQ 管理配置（spec 034 §10 / T20）。前缀 {@code payment.mq.dlq-admin}。
 *
 * <p><b>默认全关</b>：DLQ 管理端点是非常规运维能力，不开启时连 Bean 都不注册
 * （{@code enabled} 由 {@code @ConditionalOnProperty} 消费），把攻击面压到零。</p>
 */
@ConfigurationProperties(prefix = "payment.mq.dlq-admin")
public class MqDlqAdminProperties {

    /** 管理端点开关（默认 false）。关闭时仅 {@code mq_dlq_size} gauge 与 service 可用。 */
    private boolean enabled = false;

    /**
     * X-Admin-Token 守卫令牌（与 {@code payment.resolve.admin-token} 同模式）：
     * <ul>
     *   <li>端点启用但本值为空：默认拒绝（503 锁死），杜绝「开了端点忘配令牌」的裸奔；</li>
     *   <li>请求头 {@code X-Admin-Token} 与本值不一致：403。</li>
     * </ul>
     */
    private String adminToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }
}
