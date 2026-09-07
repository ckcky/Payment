package com.payment.mockchannel.web;

import com.payment.mockchannel.config.MockChannelProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * 渠道<b>退款</b>回调代理（spec 019 / T111，ADR-0067）：以"渠道"身份向 payment-service
 * 推送退款异步回调——演示「受理 → 延迟 → 回调」中回调一环的报文形态（签名、双号寻址）。
 *
 * <p>upstream：{@code POST {payment}/internal/refunds/{refundNo}/channel-callback}（PMRF 业务单号），
 * 签名方式与支付回调一致（{@code X-Channel-Timestamp} + {@code X-Channel-Signature}，
 * HMAC-SHA256 over {@code timestamp + "." + rawBody}）。{@code signMode} 语义同支付回调：
 * VALID / FORGED（演示 403 fail-closed）/ NONE（缺头被拒）。</p>
 *
 * <p>说明：进程内 Mock 渠道（payment.channel.refund-async）已实现自动的「受理 + 延迟推送」；
 * 本端点用于人工演示 / 回调丢失后手工补推（与 resolve 人工收敛互补）。</p>
 */
@RestController
public class RefundCallbackProxy {

    private static final Logger log = LoggerFactory.getLogger(RefundCallbackProxy.class);

    private final MockChannelProperties properties;
    private final RestClient restClient;

    public RefundCallbackProxy(MockChannelProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    /** 回调请求体：{@code {refundNo, status, channelReference, reason, signMode}}。仅前两者必填。 */
    public record RefundCallbackRequest(String refundNo, String status, String channelReference,
                                        String reason, String signMode) {
    }

    @PostMapping("/mock-channel/refund-callback")
    public ResponseEntity<Map<String, Object>> refundCallback(@RequestBody RefundCallbackRequest request) {
        String paymentUrl = properties.serviceUrl("payment");
        if (paymentUrl == null) {
            return ResponseEntity.internalServerError().body(Map.of("error", "mock-channel.services.payment not configured"));
        }
        if (!StringUtils.hasText(request.refundNo()) || !StringUtils.hasText(request.status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "refundNo and status are required"));
        }

        Map<String, Object> upstreamBody = new java.util.LinkedHashMap<>();
        upstreamBody.put("status", request.status());
        upstreamBody.put("channelReference", request.channelReference());
        upstreamBody.put("reason", request.reason());
        String body = ChannelCallbackProxy.SimpleJson.serialize(upstreamBody);

        String mode = StringUtils.hasText(request.signMode()) ? request.signMode() : "VALID";
        String secret = properties.getSecret() == null ? "" : properties.getSecret();

        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> signatureHeaders = ChannelCallbackProxy.signHeaders(secret, body, mode);
        if (signatureHeaders == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid signMode: " + mode));
        }
        signatureHeaders.forEach(headers::set);

        String url = paymentUrl + "/internal/refunds/" + request.refundNo() + "/channel-callback";
        log.info("[demo] refund callback -> payment {} (signMode={}, status={})", url, mode, request.status());
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
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("upstreamStatus", e.getStatusCode().value(),
                            "body", e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("upstreamStatus", 500, "body", "proxy error: " + e.getMessage()));
        }
    }
}
