package com.payment.channelgateway.domain;

import com.payment.common.core.dye.DyeMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec 030 / FR-302 / FR-304：{@code ChannelOrder} 的渠道模态派生。
 *
 * <p>核心断言分两侧：
 * <ul>
 *   <li><b>读取侧 fail-safe（T54）</b>：四类坏数据一律 {@code MOCK}，不抛异常、不误判 SANDBOX；</li>
 *   <li><b>写入侧（T53）</b>：写入的键存在且取值合法。</li>
 * </ul>
 */
class ChannelOrderChannelModeTest {

    private static ChannelOrder attempt() {
        return new ChannelOrder("PM001", "MOCK", 0, 1000L, "CNY");
    }

    // ---- T54：读取侧 fail-safe（FR-304 / SC-A-11） ----

    @Test
    @DisplayName("坏数据① extra == null（存量行 extra_json IS NULL）⇒ MOCK")
    void nullExtraFallsBackToMock() {
        ChannelOrder a = attempt();
        assertNull(a.getExtra());
        assertEquals(DyeMode.MOCK, a.getChannelMode());
    }

    @Test
    @DisplayName("坏数据② 非法 JSON（反序列化失败 ⇒ 上层传 null）⇒ MOCK，不抛异常")
    void undecodableJsonFallsBackToMock() {
        // 模拟 AttemptExtraCodec.decode 对坏数据的 fail-safe 输出：null
        ChannelOrder a = ChannelOrder.rehydrate(1L, "PM001", "MOCK", 0, null, null, null,
                ChannelOrderStatus.PENDING, null, null, 1, "PAYMENT", 1000L, "CNY", null);
        assertEquals(DyeMode.MOCK, a.getChannelMode());
    }

    @Test
    @DisplayName("坏数据③ 缺 channelMode 键 ⇒ MOCK")
    void missingKeyFallsBackToMock() {
        ChannelOrder a = attempt();
        a.putExtra("someOtherKey", "value");
        assertEquals(DyeMode.MOCK, a.getChannelMode());
    }

    @Test
    @DisplayName("坏数据④ channelMode 值不在 {MOCK,SANDBOX} ⇒ MOCK（绝不误判为 SANDBOX）")
    void illegalValueFallsBackToMock() {
        assertEquals(DyeMode.MOCK, withMode("PRODUCTION").getChannelMode());
        assertEquals(DyeMode.MOCK, withMode("sandboxx").getChannelMode());
        assertEquals(DyeMode.MOCK, withMode("").getChannelMode());
        assertEquals(DyeMode.MOCK, withMode("   ").getChannelMode());
    }

    @Test
    @DisplayName("合法值：SANDBOX 原样读出；大小写不敏感")
    void legalValuesRoundTrip() {
        assertEquals(DyeMode.SANDBOX, withMode("SANDBOX").getChannelMode());
        assertEquals(DyeMode.SANDBOX, withMode("sandbox").getChannelMode());
        assertEquals(DyeMode.SANDBOX, withMode(" Sandbox ").getChannelMode());
        assertEquals(DyeMode.MOCK, withMode("mock").getChannelMode());
    }

    // ---- T53：写入侧（FR-303） ----

    @Test
    @DisplayName("写入侧：channelMode 键存在且取值合法（新写入行 MUST NOT 缺失该键）")
    void writtenModeIsPresentAndLegal() {
        ChannelOrder a = attempt();
        a.putExtra(ChannelOrder.CHANNEL_MODE_KEY, DyeMode.SANDBOX.name());

        Map<String, String> extra = a.getExtra();
        assertNotNull(extra, "写入后 extra 不得为 null");
        assertTrue(extra.containsKey(ChannelOrder.CHANNEL_MODE_KEY), "新写入行必须含 channelMode 键");
        assertEquals("SANDBOX", extra.get(ChannelOrder.CHANNEL_MODE_KEY));
        assertEquals(DyeMode.SANDBOX, a.getChannelMode());
    }

    @Test
    @DisplayName("rehydrate 带 extra 还原：模态随行恢复（反向路径读得到）")
    void rehydrateRestoresMode() {
        ChannelOrder a = ChannelOrder.rehydrate(1L, "PM001", "ALIPAY", 0, null, null, "ch-ref",
                ChannelOrderStatus.ACCEPTED, null, null, 1, "PAYMENT", 1000L, "CNY",
                Map.of(ChannelOrder.CHANNEL_MODE_KEY, "SANDBOX"));
        assertEquals(DyeMode.SANDBOX, a.getChannelMode());
    }

    @Test
    @DisplayName("rehydrate 旧签名（无 extra）⇒ MOCK，既有调用点零改动 [SC-A-02]")
    void legacyRehydrateKeepsMock() {
        ChannelOrder a = ChannelOrder.rehydrate(1L, "PM001", "ALIPAY", 0, null, null, "ch-ref",
                ChannelOrderStatus.ACCEPTED, null, null, 1, "PAYMENT", 1000L, "CNY");
        assertNull(a.getExtra());
        assertEquals(DyeMode.MOCK, a.getChannelMode());
    }

    @Test
    @DisplayName("getExtra 返回只读视图：外部改不动领域内部状态")
    void extraIsReadOnly() {
        ChannelOrder a = withMode("SANDBOX");
        Map<String, String> extra = a.getExtra();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> extra.put("x", "y"));
    }

    private static ChannelOrder withMode(String mode) {
        ChannelOrder a = attempt();
        a.putExtra(ChannelOrder.CHANNEL_MODE_KEY, mode);
        return a;
    }
}
