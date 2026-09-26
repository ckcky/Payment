package com.payment.channelgateway.domain;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.channelgateway.infra.persistence.InMemoryChannelOrderRepository;
import com.payment.channelgateway.infra.persistence.AttemptExtraCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 030 / T122 / FR-301~FR-304 / SC-A-11：{@code extra_json} 载体端到端契约。
 *
 * <p>{@code ChannelOrderChannelModeTest} 已分别锁死<b>领域侧</b>的读/写语义；
 * {@code AttemptExtraCodecTest} 已锁死<b>编解码侧</b>的 fail-safe。本类的价值在于
 * <b>把三段串起来</b>——「开尝试（写入口）→ 落库（编码）→ 重建（解码）→ 读模态」，
 * 这是任何单层测试都覆盖不到的结合部：单层都对、接起来错（如参数序写反、键名拼写漂移）
 * 正是模态落库类缺陷的典型形态。</p>
 *
 * <p>断言四件事：
 * <ol>
 *   <li><b>写入侧强制含键</b>（FR-303）：经写入口开的尝试，{@code extra} 必有
 *       {@value ChannelOrder#CHANNEL_MODE_KEY} 且取值合法——不依赖调用方自觉；</li>
 *   <li><b>读取侧四类坏数据 fail-safe 一律 MOCK</b>（FR-304 / SC-A-11）：串到真实
 *       仓储读路径上验证，而非只测领域方法；</li>
 *   <li><b>幂等重复不覆盖</b>（FR-303③）：同一 attempt 重复开/重复盖章，
 *       模态不被后来的值改写；</li>
 *   <li><b>存量 {@code NULL} ⇒ MOCK</b>（FR-307）：不回填存量行也不误判。</li>
 * </ol>
 */
class ChannelOrderExtraJsonTest {

    private static final String PAYMENT_NO = "PM-EXTRA-1";

    private final InMemoryChannelOrderRepository attempts = new InMemoryChannelOrderRepository();

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    // ---- ① 写入侧：写入口强制盖章（FR-303①） ----

    @Test
    @DisplayName("写入口开尝试 ⇒ extra 必含 channelMode 键且取值合法（不依赖调用方自觉）[FR-303]")
    void openAttemptStampsModeKey() {
        DyeContext.runWith(DyeMode.SANDBOX, () -> attempts.openChannelOrder(PAYMENT_NO, "ALIPAY", 100L, "CNY"));

        ChannelOrder saved = attempts.findByPaymentNo(PAYMENT_NO).get(0);
        assertThat(saved.getExtra())
                .as("写入口必须盖章，键缺失意味着反向路径永远读不到模态")
                .containsKey(ChannelOrder.CHANNEL_MODE_KEY);
        assertThat(saved.getExtra().get(ChannelOrder.CHANNEL_MODE_KEY)).isIn("MOCK", "SANDBOX");
        assertThat(saved.getChannelMode()).isEqualTo(DyeMode.SANDBOX);
    }

    @Test
    @DisplayName("不染色开尝试 ⇒ 盖章 MOCK（缺省即本地 mock，FR-160）")
    void openAttemptWithoutDyeStampsMock() {
        DyeContext.clear();
        attempts.openChannelOrder(PAYMENT_NO, "MOCK", 100L, "CNY");

        ChannelOrder saved = attempts.findByPaymentNo(PAYMENT_NO).get(0);
        assertThat(saved.getExtra().get(ChannelOrder.CHANNEL_MODE_KEY)).isEqualTo("MOCK");
        assertThat(saved.getChannelMode()).isEqualTo(DyeMode.MOCK);
    }

    @Test
    @DisplayName("退款尝试同样盖章（开退款尝试也走同一写入口口径）[FR-151]")
    void openRefundAttemptStampsModeKey() {
        DyeContext.runWith(DyeMode.SANDBOX, () -> attempts.openRefundAttempt(PAYMENT_NO, "ALIPAY", 100L, "CNY"));

        ChannelOrder saved = attempts.findByPaymentNo(PAYMENT_NO).get(0);
        assertThat(saved.getAttemptType()).isEqualTo(ChannelOrder.TYPE_REFUND);
        assertThat(saved.getChannelMode()).isEqualTo(DyeMode.SANDBOX);
    }

    // ---- ② 读取侧：四类坏数据 fail-safe（FR-304 / SC-A-11） ----

    @Test
    @DisplayName("坏数据① 存量行 extra_json IS NULL ⇒ MOCK，且不抛异常 [FR-307][SC-A-11]")
    void nullExtraReadsAsMock() {
        // 模拟真库读出的存量行：extra_json 为 NULL ⇒ 解码返回 null
        Map<String, String> decoded = AttemptExtraCodec.decode(null);
        ChannelOrder legacy = ChannelOrder.rehydrate(1L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now(), null, "ch-old", ChannelOrderStatus.ACCEPTED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", decoded);

        assertThat(legacy.getExtra()).isNull();
        assertThat(legacy.getChannelMode()).isEqualTo(DyeMode.MOCK);
    }

    @Test
    @DisplayName("坏数据② 非法 JSON ⇒ 解码 null ⇒ 模态 MOCK，绝不抛异常打断反向路径 [SC-A-11]")
    void brokenJsonReadsAsMock() {
        Map<String, String> decoded = AttemptExtraCodec.decode("{not-a-json");
        assertThat(decoded).isNull();

        ChannelOrder attempt = ChannelOrder.rehydrate(1L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now(), null, "ch-old", ChannelOrderStatus.ACCEPTED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", decoded);
        assertThat(attempt.getChannelMode()).isEqualTo(DyeMode.MOCK);
    }

    @Test
    @DisplayName("坏数据③ 合法 JSON 但缺 channelMode 键 ⇒ MOCK [SC-A-11]")
    void missingKeyReadsAsMock() {
        Map<String, String> decoded = AttemptExtraCodec.decode("{\"someOtherKey\":\"v\"}");
        ChannelOrder attempt = ChannelOrder.rehydrate(1L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now(), null, null, ChannelOrderStatus.ACCEPTED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", decoded);

        assertThat(attempt.getChannelMode()).isEqualTo(DyeMode.MOCK);
    }

    @Test
    @DisplayName("坏数据④ channelMode 值非法 ⇒ 一律 MOCK，绝不误判为 SANDBOX [SC-A-11]")
    void illegalValueNeverReadsAsSandbox() {
        for (String bad : new String[] {"PRODUCTION", "sandboxx", "", "   ", "123", "true"}) {
            Map<String, String> decoded = AttemptExtraCodec.decode("{\"channelMode\":\"" + bad + "\"}");
            ChannelOrder attempt = ChannelOrder.rehydrate(1L, PAYMENT_NO, "ALIPAY", 0,
                    Instant.now(), null, null, ChannelOrderStatus.ACCEPTED, null, null, 1,
                    ChannelOrder.TYPE_PAYMENT, 100L, "CNY", decoded);

            assertThat(attempt.getChannelMode())
                    .as("坏值 %s 必须 fail-safe 落 MOCK（误判 SANDBOX 会让反向路径去连真实渠道）", bad)
                    .isEqualTo(DyeMode.MOCK);
        }
    }

    // ---- ③ 幂等重复不覆盖（FR-303③） ----

    @Test
    @DisplayName("重复盖章同值 ⇒ 模态稳定，不因重复写而漂移 [FR-303③]")
    void repeatedStampingIsIdempotent() {
        DyeContext.runWith(DyeMode.SANDBOX, () -> attempts.openChannelOrder(PAYMENT_NO, "ALIPAY", 100L, "CNY"));
        ChannelOrder attempt = attempts.findByPaymentNo(PAYMENT_NO).get(0);

        // 同一模态重复盖章（模拟重复入口调用）
        attempt.putExtra(ChannelOrder.CHANNEL_MODE_KEY, DyeMode.SANDBOX.name());
        attempt.putExtra(ChannelOrder.CHANNEL_MODE_KEY, DyeMode.SANDBOX.name());

        assertThat(attempt.getExtra().get(ChannelOrder.CHANNEL_MODE_KEY)).isEqualTo("SANDBOX");
        assertThat(attempt.getChannelMode()).isEqualTo(DyeMode.SANDBOX);

        // 再经一次完整落库-重建往返，模态不丢
        String json = AttemptExtraCodec.encode(attempt.getExtra());
        ChannelOrder reloaded = ChannelOrder.rehydrate(attempt.getId(), PAYMENT_NO, "ALIPAY", 0,
                attempt.getRequestedAt(), null, null, ChannelOrderStatus.ACCEPTED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", AttemptExtraCodec.decode(json));
        assertThat(reloaded.getChannelMode()).isEqualTo(DyeMode.SANDBOX);
    }

    @Test
    @DisplayName("落库往返：SANDBOX 写进 extra_json 再读出仍是 SANDBOX（键名两侧不漂移）[FR-303]")
    void fullRoundTripKeepsSandbox() {
        DyeContext.runWith(DyeMode.SANDBOX, () -> attempts.openChannelOrder(PAYMENT_NO, "ALIPAY", 100L, "CNY"));
        ChannelOrder original = attempts.findByPaymentNo(PAYMENT_NO).get(0);

        // 写侧：Map → extra_json 列
        String columnValue = AttemptExtraCodec.encode(original.getExtra());
        assertThat(columnValue).contains("\"channelMode\"").contains("SANDBOX");

        // 读侧：extra_json 列 → Map → 模态
        ChannelOrder reloaded = ChannelOrder.rehydrate(original.getId(), PAYMENT_NO, "ALIPAY", 0,
                original.getRequestedAt(), null, null, ChannelOrderStatus.ACCEPTED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", AttemptExtraCodec.decode(columnValue));

        assertThat(reloaded.getChannelMode()).isEqualTo(DyeMode.SANDBOX);
    }

    // ---- ④ 存量 NULL 不回填（FR-307 / FR-308） ----

    @Test
    @DisplayName("存量行不回填：读 MOCK 但 extra 仍为 null，不伪造『已确认模态』的事实 [FR-307]")
    void legacyRowIsNotBackfilled() {
        ChannelOrder legacy = ChannelOrder.rehydrate(1L, PAYMENT_NO, "MOCK", 0,
                Instant.now(), null, null, ChannelOrderStatus.SUCCEEDED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", null);

        assertThat(legacy.getChannelMode()).isEqualTo(DyeMode.MOCK);
        // 关键：读取动作本身不产生写入副作用
        assertThat(legacy.getExtra()).isNull();
    }
}
