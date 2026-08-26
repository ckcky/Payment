package com.payment.common.core.observability;

import com.payment.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化资金审计日志（Constitution §6 / engineering-standards §7）。
 *
 * <p>资金动作（支付、退款、结算、记账）必须单独落一条审计日志：使用专用 logger
 * {@code FINANCIAL_AUDIT}（可独立路由到审计文件/采集器），单行 JSON，含幂等键、金额、币种、
 * 状态流转与 traceId 关联。金额始终为 {@link Long}（最小货币单位），绝不使用 float/double。</p>
 *
 * <p>本类为普通 POJO（不加 {@code @Component}），由 {@code CommonCoreAutoConfiguration} 注册。</p>
 */
public class StructuredAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger("FINANCIAL_AUDIT");

    /**
     * 记录一条资金审计事件。
     *
     * @param action         动作名（如 payment.succeeded / refund.unknown）
     * @param idempotencyKey 幂等键（可为 null）
     * @param amountMinor    金额，最小货币单位（Long；null 表示无金额）
     * @param currencyCode   币种（如 CNY，可为 null）
     * @param fromStatus     流转前状态（可为 null）
     * @param toStatus       流转后状态（可为 null）
     * @param entityType     实体类型（如 payment / refund，可为 null）
     * @param entityId       实体 ID（可为 null）
     */
    public void audit(String action, String idempotencyKey, Long amountMinor, String currencyCode,
                      String fromStatus, String toStatus, String entityType, String entityId) {
        if (!LOG.isInfoEnabled()) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", action);
        fields.put("traceId", TraceContext.getOrCreate());
        putIfNotNull(fields, "idempotencyKey", idempotencyKey);
        putIfNotNull(fields, "amountMinor", amountMinor);
        putIfNotNull(fields, "currencyCode", currencyCode);
        putIfNotNull(fields, "fromStatus", fromStatus);
        putIfNotNull(fields, "toStatus", toStatus);
        putIfNotNull(fields, "entityType", entityType);
        putIfNotNull(fields, "entityId", entityId);
        LOG.info(toJson(fields));
    }

    /**
     * 敏感信息脱敏（卡号 / 密钥）：null 或长度 &le; 4 返回 {@code "***"}；否则保留前 2 与后 2 个
     * 字符，中间替换为 {@code "***"}（如 {@code "4111111111111111"} → {@code "41***16"}）。
     */
    public static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private static void putIfNotNull(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    private static String toJson(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder(128).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
