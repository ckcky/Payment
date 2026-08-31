package com.payment.mockchannel.web;

import com.payment.common.core.security.SignatureVerifier;
import com.payment.mockchannel.config.MockChannelProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * 渠道回调代理：以"渠道"身份向 payment-service 发送签名回调（ADR-0048 修订 + ADR-0052）。
 *
 * <p>upstream：{@code POST {payment}/internal/payments/{paymentId}/channel-callback}，
 * 携带 {@code X-Channel-Timestamp} + {@code X-Channel-Signature}（HMAC-SHA256 over
 * {@code timestamp + "." + rawBody}，与 payment-service 的验签过滤器同一实现）。</p>
 *
 * <p>{@code signMode} 支持三种演示形态：</p>
 * <ul>
 *   <li>{@code VALID}：正常签名（默认）；</li>
 *   <li>{@code FORGED}：用错误密钥签名 —— 演示 payment 侧 403 fail-closed；</li>
 *   <li>{@code NONE}：不带签名头 —— 演示"缺头被拒"。</li>
 * </ul>
 *
 * <p>"重复回调"与"超时不回调"两种演示由前端页面直接编排（连发两次请求 / 不发请求），
 * 代理层保持单一职责。</p>
 */
@RestController
public class ChannelCallbackProxy {

    private static final Logger log = LoggerFactory.getLogger(ChannelCallbackProxy.class);

    private final MockChannelProperties properties;
    private final RestClient restClient;

    public ChannelCallbackProxy(MockChannelProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 回调请求体：{@code {paymentId, status, channelReference, reason, amountMinor, signMode}}。
     * 仅 {@code paymentId/status} 必填，其余可空（与 ChannelCallbackRequest 对齐）。
     */
    public record CallbackRequest(Long paymentId, String status, String channelReference,
                                  String reason, Long amountMinor, String signMode) {
    }

    @PostMapping("/mock-channel/callback")
    public ResponseEntity<Map<String, Object>> callback(@RequestBody CallbackRequest request) {
        String paymentUrl = properties.serviceUrl("payment");
        if (paymentUrl == null) {
            return ResponseEntity.internalServerError().body(Map.of("error", "mock-channel.services.payment not configured"));
        }

        // upstream body 只含业务字段（signMode 是本组件的演示开关，不下传）
        Map<String, Object> upstreamBody = new java.util.LinkedHashMap<>();
        upstreamBody.put("status", request.status());
        upstreamBody.put("channelReference", request.channelReference());
        upstreamBody.put("reason", request.reason());
        upstreamBody.put("amountMinor", request.amountMinor());
        String body = SimpleJson.serialize(upstreamBody);

        String mode = StringUtils.hasText(request.signMode()) ? request.signMode() : "VALID";
        String secret = properties.getSecret() == null ? "" : properties.getSecret();

        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> signatureHeaders = signHeaders(secret, body, mode);
        if (signatureHeaders == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid signMode: " + mode));
        }
        signatureHeaders.forEach(headers::set);

        String url = paymentUrl + "/internal/payments/" + request.paymentId() + "/channel-callback";
        log.info("[demo] channel callback -> payment {} (signMode={}, status={})", url, mode, request.status());
        try {
            ResponseEntity<String> upstream = restClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            return ResponseEntity.status(upstream.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("upstreamStatus", upstream.getStatusCode().value(),
                            "body", upstream.getBody() == null ? "" : upstream.getBody()));
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // 上游 4xx/5xx（如 403 验签失败）：原样回传状态码与错误体，供页面展示
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("upstreamStatus", e.getStatusCode().value(),
                            "body", e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("upstreamStatus", 500, "body", "proxy error: " + e.getMessage()));
        }
    }

    /**
     * 按 signMode 生成签名头（{@code X-Channel-Timestamp} / {@code X-Channel-Signature}）。
     *
     * @return VALID → 正常签名；FORGED → 用错误密钥签名（payment 侧验签必然失败 → 403）；
     *         NONE → 空 map（不带任何签名头，演示"缺头被拒"）；未知 mode → {@code null}
     */
    static Map<String, String> signHeaders(String secret, String body, String mode) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        return switch (mode) {
            case "VALID" -> Map.of(
                    "X-Channel-Timestamp", timestamp,
                    "X-Channel-Signature", SignatureVerifier.sign(secret, timestamp, body));
            case "FORGED" -> Map.of(
                    "X-Channel-Timestamp", timestamp,
                    "X-Channel-Signature", SignatureVerifier.sign("forged-wrong-secret", timestamp, body));
            case "NONE" -> Map.of();
            default -> null;
        };
    }

    /** 极简 JSON 序列化（字段值均为 string/number/null，避免引入 Jackson 依赖到 controller 逻辑）。 */
    static final class SimpleJson {

        private SimpleJson() {
        }

        static String serialize(Map<String, Object> fields) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(e.getKey())).append(':');
                Object v = e.getValue();
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number) {
                    sb.append(v);
                } else {
                    sb.append(quote(String.valueOf(v)));
                }
            }
            return sb.append('}').toString();
        }

        private static String quote(String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }
}
