package com.payment.common.mq;

/**
 * Redis 键命名集中定义（spec 029 / ADR-0074 D4）。
 *
 * <p><b>集中在一处的理由</b>：键名是通道的对外契约之一——排障脚本、演示、Grafana 面板
 * 都直连 redis-cli 按这些前缀检索；散落在各处改名即悄悄破坏排障链路。</p>
 */
public final class MqKeys {

    /** 可见消息 Stream 前缀：{@code mq:stream:{topic}}（XADD 目标 / XREADGROUP 来源）。 */
    public static final String STREAM = "mq:stream:";

    /** 半消息前缀：{@code mq:half:{topic}:{msgId}}（Hash，commit 前不可见）。 */
    public static final String HALF = "mq:half:";

    /** 半消息回查索引（ZSet，score = prepare 时刻毫秒，member = topic:msgId）。 */
    public static final String HALF_IDX = "mq:half:idx";

    /** 死信 Stream 前缀：{@code mq:dlq:{topic}}。 */
    public static final String DLQ = "mq:dlq:";

    /** 半消息回查计数前缀：{@code mq:half:checks:{topic}:{msgId}}。 */
    public static final String HALF_CHECKS = "mq:half:checks:";

    /** 消费组名（每个 topic 上由订阅方各自建租）。 */
    public static final String GROUP_SUFFIX = ":group";

    private MqKeys() {
    }

    public static String stream(String topic) {
        return STREAM + topic;
    }

    public static String half(String topic, String msgId) {
        return HALF + topic + ":" + msgId;
    }

    /** 半消息索引 member：{@code topic:msgId}。 */
    public static String halfIdxMember(String topic, String msgId) {
        return topic + ":" + msgId;
    }

    public static String dlq(String topic) {
        return DLQ + topic;
    }
}
