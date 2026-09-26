package com.payment.channelgateway.infra.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * spec 030 / FR-302：{@code extra_json} 编解码。
 *
 * <p>关键纪律：
 * <ul>
 *   <li>领域层 MUST NOT 依赖 Jackson —— 编解码只在本类；</li>
 *   <li>读侧 fail-safe：坏 JSON ⇒ {@code null}（⇒ 模态判 MOCK），<b>绝不抛异常</b>；</li>
 *   <li>写侧：{@code null}/空 ⇒ {@code NULL} 列值，不造空 JSON 对象。</li>
 * </ul>
 */
class AttemptExtraCodecTest {

    @Test
    @DisplayName("写：null / 空 Map ⇒ NULL 列值（不为『什么都没记』造空对象）")
    void encodeNullAndEmpty() {
        assertNull(AttemptExtraCodec.encode(null));
        assertNull(AttemptExtraCodec.encode(Map.of()));
    }

    @Test
    @DisplayName("写+读：Map 往返一致")
    void roundTrip() {
        Map<String, String> extra = Map.of("channelMode", "SANDBOX");
        String json = AttemptExtraCodec.encode(extra);
        assertEquals("{\"channelMode\":\"SANDBOX\"}", json);
        assertEquals(extra, AttemptExtraCodec.decode(json));
    }

    @Test
    @DisplayName("读：null / 空白 ⇒ null（不抛异常）")
    void decodeNullAndBlank() {
        assertNull(AttemptExtraCodec.decode(null));
        assertNull(AttemptExtraCodec.decode(""));
        assertNull(AttemptExtraCodec.decode("   "));
    }

    @Test
    @DisplayName("读：非法 JSON ⇒ null（fail-safe，绝不抛异常打断反向路径）[SC-A-11]")
    void decodeBrokenJson() {
        assertNull(AttemptExtraCodec.decode("{not json"));
        assertNull(AttemptExtraCodec.decode("[1,2,3]")); // 是 JSON 但不是对象
        assertNull(AttemptExtraCodec.decode("{}"));      // 空对象等价于无扩展属性
    }

    @Test
    @DisplayName("读：非字符串值不崩 —— Jackson 会 coerce 成 \"123\"，由领域侧 fail-safe 兜成 MOCK")
    void decodeTypeMismatchIsCaughtByDomainFailsafe() {
        // Jackson 默认允许标量转字符串，故这里拿得到 {channelMode=123} 而非 null。
        Map<String, String> decoded = AttemptExtraCodec.decode("{\"channelMode\":123}");
        assertEquals("123", decoded.get("channelMode"));

        // 两层防御的第二层：领域侧 parse("123") 抛 IllegalArgumentException ⇒ 一律落 MOCK。
        // 这正是 FR-304 要求的「MUST NOT 误判为 SANDBOX、MUST NOT 中断反向路径」。
        com.payment.channelgateway.domain.ChannelOrder attempt =
                new com.payment.channelgateway.domain.ChannelOrder("PM001", "MOCK", 0, 1000L, "CNY");
        attempt.putExtra("channelMode", decoded.get("channelMode"));
        assertEquals(com.payment.common.core.dye.DyeMode.MOCK, attempt.getChannelMode());
    }
}
