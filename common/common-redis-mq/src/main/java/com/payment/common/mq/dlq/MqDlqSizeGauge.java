package com.payment.common.mq.dlq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.MqKeys;
import com.payment.common.mq.MqTopics;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DLQ 积压 gauge（spec 034 §10 / T20）：{@code mq_dlq_size{topic=...}} = 各已知 topic
 * 死信 Stream 的 XLEN，Prometheus 抓取期采样（抓取频率即采样频率）。
 *
 * <p>挂靠告警 A-06（{@code sum(mq_dlq_size) by (topic) > 0}）：死信非零即为异常态——
 * 正常消费路径超限进 DLQ 本就是「处理失败被兜住」，留着不处理等于丢消息。</p>
 *
 * <p>抓取期 Redis 抖动不传播：回调内捕获，返回 {@code null}（Micrometer 记 NaN，下轮恢复）。</p>
 */
public class MqDlqSizeGauge {

    public MqDlqSizeGauge(StringRedisTemplate redis, BusinessMetrics metrics) {
        for (String topic : MqTopics.ALL) {
            metrics.gauge("mq_dlq_size", () -> {
                try {
                    Long size = redis.opsForStream().size(MqKeys.dlq(topic));
                    return size == null ? 0L : size;
                } catch (RuntimeException ex) {
                    return null;
                }
            }, "topic", topic);
        }
    }
}
