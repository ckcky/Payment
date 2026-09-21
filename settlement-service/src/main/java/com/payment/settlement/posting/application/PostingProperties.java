package com.payment.settlement.posting.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 出站失败台账配置（spec 034 §9：参数对齐 {@code payment.reliability.*} 风格，不引入新配置族语义）。
 *
 * <p>退避序列与重试上限为 spec §5 X-6 政策表默认值（H-034-4 批准按表起，随 035 SLO 一并确认）：
 * 登记（retry_count=0）后 1s 首补，其后 5s / 30s / 2m / 10m；第 5 次补投失败
 * （retry_count 达 6）置 ABANDONED（plan §2.2）。</p>
 */
@Component
@ConfigurationProperties(prefix = "payment.posting")
public class PostingProperties {

    /** 补投扫描调度间隔（毫秒），由 {@code @Scheduled(fixedDelay)} 使用。默认 10s。 */
    private long retryIntervalMs = 10_000;

    /** 补投退避序列（spec §5：1s/5s/30s/2m/10m；索引 = retryCount，越界取最后一项）。 */
    private List<Duration> retryBackoff = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10));

    /** 单轮补投最大行数（防重试风暴放大，spec §16 风险 2 的有界要求之一）。 */
    private int batchSize = 50;

    /** 台账人工队列视图单页上限。 */
    private int listLimit = 100;

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public List<Duration> getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(List<Duration> retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getListLimit() {
        return listLimit;
    }

    public void setListLimit(int listLimit) {
        this.listLimit = listLimit;
    }

    /**
     * retryCount → 该行下一次补投的退避时长（越界取最后一项）。
     * retry_count 口径 = 已失败投递次数（登记即 1），故退避索引取 {@code retryCount - 1}：
     * 登记（1）→ backoff[0]=1s 首补；第 n 次补投失败 → retryCount=n+1 → backoff[n]。
     */
    public Duration backoffFor(int retryCount) {
        List<Duration> backoff = getRetryBackoff();
        int index = Math.min(Math.max(retryCount - 1, 0), backoff.size() - 1);
        return backoff.get(index);
    }
}
