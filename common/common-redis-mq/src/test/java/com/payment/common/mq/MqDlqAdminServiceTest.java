package com.payment.common.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.dlq.MqDlqAdminProperties;
import com.payment.common.mq.dlq.MqDlqAdminController;
import com.payment.common.mq.dlq.MqDlqAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TT-10（spec 034 §11，L2a in-JVM 真 Redis）：DLQ 超次进死信 → 读取可见 →
 * 原样回放后被正常消费且<b>幂等吸收不双处理</b>；损坏条目拒绝回放原样保留；清空归零。
 * 附：MqDlqAdminController 守卫语义（503/403/404）纯对象级验证。
 */
class MqDlqAdminServiceTest {

    private RedisServer redisServer;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private TransactionalProducer producer;
    private MqProperties props;
    private MqDlqAdminService admin;
    private static final String TOPIC = "order.paid";

    @BeforeEach
    void setUp() throws IOException {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        props = new MqProperties();
        producer = new TransactionalProducer(redis, props, new NoOpMetrics(), "test");
        admin = new MqDlqAdminService(redis, null);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (factory != null) {
            factory.destroy();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Test
    @DisplayName("TT-10 超次进 DLQ → 读取可见 → 原样回放 → 消费端幂等吸收，不双处理")
    void dlqLifecycleReadReplayIdempotentAbsorb() {
        props.setMaxRetry(2);
        props.setRetryBackoffBase(Duration.ofMillis(1));
        EventEnvelope e = producer.envelope(TOPIC, "ORD-DLQ-1", Map.of("orderNo", "ORD-DLQ-1"));
        producer.prepare(e);
        producer.commit(e);

        AtomicInteger failAttempts = new AtomicInteger();
        StreamConsumer failing = new StreamConsumer(redis, props, new NoOpMetrics(), TOPIC, "g-fail", "c-fail",
                env -> {
                    failAttempts.incrementAndGet();
                    throw new IllegalStateException("模拟消费永久失败");
                }, producer);
        failing.ensureGroup();
        failing.pollOnce();

        assertThat(failAttempts.get()).as("1 + maxRetry 次尝试").isEqualTo(3);
        assertThat(admin.size(TOPIC)).as("超重试 → 进 DLQ").isEqualTo(1L);

        // 死信可见性：XRANGE 读出原信封 + dlqReason
        var entries = admin.list(TOPIC, 100);
        assertThat(entries).hasSize(1);
        var entry = entries.get(0);
        assertThat(entry.msgId()).as("原 msgId 保留（重放 MUST 走原 msgId，spec §10 红线）")
                .isEqualTo(e.msgId());
        assertThat(entry.eventType()).isEqualTo(e.eventType());
        assertThat(entry.bizNo()).isEqualTo("ORD-DLQ-1");
        assertThat(entry.traceId()).isEqualTo(e.traceId());
        assertThat(entry.dlqReason()).as("入死信原因可见").isNotBlank();

        // 消费端（模拟业务幂等：已应用集合按 msgId 去重）。先正常消费一次原投递，
        // 再回放死信——重放交付的是原 msgId，业务副作用 MUST 只发生一次。
        java.util.Set<String> applied = new java.util.HashSet<>();
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger businessEffects = new AtomicInteger();
        AtomicInteger absorbed = new AtomicInteger();
        AtomicReference<String> replayedMsgId = new AtomicReference<>();
        StreamConsumer consumer = new StreamConsumer(redis, props, new NoOpMetrics(), TOPIC, "g-good", "c-good",
                env -> {
                    delivered.incrementAndGet();
                    replayedMsgId.set(env.msgId());
                    if (applied.add(env.msgId())) {
                        businessEffects.incrementAndGet();
                    } else {
                        absorbed.incrementAndGet();
                    }
                }, producer);
        consumer.ensureGroup();
        consumer.pollOnce();
        assertThat(businessEffects.get()).as("原投递正常消费，业务副作用一次").isEqualTo(1);

        // 回放：原 fields XADD 主 stream + XDEL DLQ
        assertThat(admin.replay(TOPIC, entry.entryId())).as("回放成功").isTrue();
        assertThat(admin.size(TOPIC)).as("回放后 DLQ 清空").isZero();

        consumer.pollOnce();

        assertThat(delivered.get()).as("原投递 + 重放各交付一次（at-least-once）").isEqualTo(2);
        assertThat(replayedMsgId.get()).as("重放交付的是原 msgId").isEqualTo(e.msgId());
        assertThat(businessEffects.get()).as("幂等吸收：重放不产生第二次业务副作用").isEqualTo(1);
        assertThat(absorbed.get()).as("重复 msgId 被业务幂等收口").isEqualTo(1);
    }

    @Test
    @DisplayName("TT-10 损坏条目拒绝回放且原样保留；clear 清空归零")
    void corruptEntryRefusedAndClearEmpties() {
        EventEnvelope bad = producer.envelope(TOPIC, "ORD-DLQ-BAD", Map.of("orderNo", "ORD-DLQ-BAD"));
        String[] fv = EnvelopeCodec.toFields(bad);
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < fv.length; i += 2) {
            fields.put(fv[i], fv[i + 1]);
        }
        fields.put("payload", "not-json{");
        org.springframework.data.redis.connection.stream.RecordId id =
                redis.opsForStream().add(com.payment.common.mq.MqKeys.dlq(TOPIC), fields);
        assertThat(admin.size(TOPIC)).isEqualTo(1L);

        assertThatThrownBy(() -> admin.replay(TOPIC, id.getValue()))
                .as("payload 不可反序列化 → 拒绝回放")
                .isInstanceOf(MqException.class);
        assertThat(admin.size(TOPIC)).as("坏条目原样保留，等人工处置").isEqualTo(1L);

        // 不存在的条目：幂等返回 false（可能已被处理/清空）
        assertThat(admin.replay(TOPIC, "9999999999999-0")).isFalse();

        // 清空
        assertThat(admin.clear(TOPIC)).isTrue();
        assertThat(admin.size(TOPIC)).as("清空后归零").isZero();
        assertThat(admin.clear(TOPIC)).as("重复清空幂等").isFalse();
    }

    @Test
    @DisplayName("端点守卫：token 未配置 503 / 不匹配 403 / 未知 topic 404 / 命中 200")
    void controllerGuardSemantics() {
        MqDlqAdminProperties p = new MqDlqAdminProperties();
        MqDlqAdminController controller = new MqDlqAdminController(admin, p);

        // 端点启用但未配置 token → 503 锁死
        assertThat(controller.overview("anything").getStatusCode().value())
                .as("启用鉴权但未配置 token：默认拒绝").isEqualTo(503);

        p.setAdminToken("s3cret");
        assertThat(controller.overview("wrong").getStatusCode().value()).isEqualTo(403);

        var ok = controller.overview("s3cret");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        MqDlqAdminController.DlqOverview overview = (MqDlqAdminController.DlqOverview) ok.getBody();
        assertThat(overview.topics()).as("覆盖全部已知 topic").hasSize(MqTopics.ALL.length);

        assertThat(controller.detail("s3cret", "typo.topic", 10).getStatusCode().value())
                .as("未知 topic → 404，防 typo 造出无人消费的流").isEqualTo(404);
        assertThat(controller.replay("s3cret", TOPIC, "9999999999999-0").getStatusCode().value())
                .as("条目不存在 → 404").isEqualTo(404);

        var detail = controller.detail("s3cret", TOPIC, 10);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static final class NoOpMetrics implements BusinessMetrics {
        @Override
        public void counter(String name, double value, String... tags) {
        }

        @Override
        public void timer(String name, Duration duration, String... tags) {
        }
    }
}
