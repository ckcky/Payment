package com.payment.common.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 消息通道配置（spec 029 / FR-110 / ADR-0074 D8）。
 *
 * <p>前缀 {@code payment.mq}。{@code enabled=false} 时全部生产方回落同步 Feign（FR-306），
 * 便于灰度与回滚。</p>
 */
@ConfigurationProperties(prefix = "payment.mq")
public class MqProperties {

    /** 总开关：false 时生产方走同步 Feign 回落路径，不 prepare 任何消息（FR-306）。 */
    private boolean enabled = true;

    /**
     * 消费端 XREADGROUP 阻塞时长（ADR-0074 D8：短阻塞避 Lettuce 池独占）。
     *
     * <p><b>不变量</b>：本值必须**严格小于** Redis 客户端命令超时
     * （{@code spring.data.redis.timeout}），否则每轮 {@code XREADGROUP} 都会在服务端
     * 阻塞等待中被客户端判定超时，消费循环空转、消息永不被处理（spec 029 回归缺陷）。
     * 由 {@code MqTimeoutGuard} 在启动期 fail-fast 校验。</p>
     */
    private Duration blockMs = Duration.ofMillis(2000);

    /** 单次 XREADGROUP 拉取条数。 */
    private int batchSize = 50;

    /** 消费失败最大重试次数，超出进 DLQ（FR-108）。 */
    private int maxRetry = 3;

    /** 半消息回查超时：prepare 超过此时长未 commit 即被扫描（FR-106）。 */
    private Duration prepareTimeoutMs = Duration.ofSeconds(30);

    /** UNKNOWN 状态最多回查次数，超出进 DLQ（FR-106）。 */
    private int maxCheckTimes = 10;

    /** XAUTOCLAIM 认领阈值：PEL 中闲置超过此时长的消息由同组实例接管（FR-109）。 */
    private Duration minIdleMs = Duration.ofSeconds(60);

    /** 消费失败退避基数：1s/2s/4s 指数退避（FR-108）。 */
    private Duration retryBackoffBase = Duration.ofSeconds(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getBlockMs() {
        return blockMs;
    }

    public void setBlockMs(Duration blockMs) {
        this.blockMs = blockMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public Duration getPrepareTimeoutMs() {
        return prepareTimeoutMs;
    }

    public void setPrepareTimeoutMs(Duration prepareTimeoutMs) {
        this.prepareTimeoutMs = prepareTimeoutMs;
    }

    public int getMaxCheckTimes() {
        return maxCheckTimes;
    }

    public void setMaxCheckTimes(int maxCheckTimes) {
        this.maxCheckTimes = maxCheckTimes;
    }

    public Duration getMinIdleMs() {
        return minIdleMs;
    }

    public void setMinIdleMs(Duration minIdleMs) {
        this.minIdleMs = minIdleMs;
    }

    public Duration getRetryBackoffBase() {
        return retryBackoffBase;
    }

    public void setRetryBackoffBase(Duration retryBackoffBase) {
        this.retryBackoffBase = retryBackoffBase;
    }
}
