package com.payment.payment.infra.persistence.attempt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * {@code payment_attempts.extra_json} 的编解码（spec 030 / FR-302）。
 *
 * <p><b>为什么单独一个类</b>：领域对象 {@link com.payment.payment.domain.PaymentAttempt} 只持有
 * {@code Map<String,String>}，<b>MUST NOT 依赖 Jackson</b>；JSON 是<b>存储细节</b>，
 * 必须留在 {@code infra/persistence}。</p>
 *
 * <h3>fail-safe 读（FR-304 / SC-A-11）</h3>
 * 反序列化失败时<b>返回 {@code null}</b> 并记 warn，<b>MUST NOT</b> 抛异常——
 * 「读不出扩展属性」的后果只是反向路径按 {@code MOCK} 处理（不去连真实渠道沙箱），
 * 而抛异常会直接打断查询 / 退款 / 超时扫描。坏数据<b>不静默吞</b>：落 warn 日志留证据。
 *
 * <h3>写（FR-301）</h3>
 * {@code null} / 空 Map ⇒ 落 {@code NULL} 列值，不为「什么都没记」造一个空 JSON 对象——
 * 空对象与「没有扩展属性」在读取侧同义，但前者会让「是否写过」这个事实变得不可判定。
 */
public final class AttemptExtraCodec {

    private static final Logger log = LoggerFactory.getLogger(AttemptExtraCodec.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
    };

    private AttemptExtraCodec() {
    }

    /**
     * Map → JSON 文本。
     *
     * @param extra 扩展属性（{@code null} 或空 ⇒ 返回 {@code null}）
     * @return 可落 {@code extra_json} 列的文本；{@code null} 表示「无扩展属性」
     */
    public static String encode(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(extra);
        } catch (Exception ex) {
            // 写侧失败不能静默：丢扩展属性会让反向路径拿不到模态，属数据丢失。
            // 但也绝不因此中断建单主流程——落 error 日志，由告警发现。
            log.error("extra_json 序列化失败，本次 attempt 的渠道模态将丢失; keys={}", extra.keySet(), ex);
            return null;
        }
    }

    /**
     * JSON 文本 → Map（fail-safe）。
     *
     * @param json 列值；{@code null} / 空白 / 非法 JSON ⇒ 返回 {@code null}
     * @return 扩展属性；{@code null} 表示「无（或读不出）扩展属性」
     */
    public static Map<String, String> decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(json, TYPE);
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception ex) {
            log.warn("extra_json 反序列化失败（fail-safe ⇒ 按无扩展属性处理，模态判为 MOCK）; 长度={}",
                    json.length(), ex);
            return null;
        }
    }
}
