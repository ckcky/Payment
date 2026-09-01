package com.payment.mockchannel.web;

import com.payment.mockchannel.config.MockChannelProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 同源演示代理：把 {@code /proxy/{service}/**} 透传到对应服务，避免 CORS（ADR-0048 修订）。
 *
 * <p>demo.html 只与 8091 同源；跨服务的所有调用（catalog / order / payment / entitlement ...）
 * 都经由本代理转发。方法、路径、查询串、body 原样透传；上游 4xx/5xx 状态码与响应体原样返回，
 * 页面可直接按状态码断言（如 409 幂等轮询语义、429 限流）。</p>
 *
 * <p><b>零服务改动</b>：不要求任何服务开放 CORS 配置（宪法：不为演示改生产契约）。</p>
 */
@RestController
public class DemoProxyController {

    private static final Logger log = LoggerFactory.getLogger(DemoProxyController.class);

    private final MockChannelProperties properties;
    private final RestClient restClient;

    public DemoProxyController(MockChannelProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    @RequestMapping("/proxy/{service}/**")
    public ResponseEntity<String> proxy(@PathVariable String service, HttpServletRequest request) {
        String baseUrl = properties.serviceUrl(service);
        if (baseUrl == null) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"unknown proxy service: " + service + "\"}");
        }

        // 提取 /proxy/{service} 之后的剩余路径：直接按字面前缀剥离，避免通配长度计算越界
        String matched = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String rest = restPath(matched, service);
        String query = request.getQueryString();
        URI target = URI.create(baseUrl + rest + (query == null ? "" : "?" + query));

        try {
            byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            log.info("[demo] proxy {} {} -> {}", method, request.getRequestURI(), target);

            ResponseEntity<String> upstream = restClient.method(method)
                    .uri(target)
                    .headers(h -> copyRequestHeaders(request, h))
                    .body(body.length == 0 ? null : new String(body, StandardCharsets.UTF_8))
                    .retrieve()
                    .toEntity(String.class);
            return ResponseEntity.status(upstream.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(upstream.getBody());
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"proxy error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "\"}");
        }
    }

    /** 供测试与运维快速确认代理可用性。 */
    @org.springframework.web.bind.annotation.GetMapping("/proxy")
    public Map<String, Object> index() {
        return Map.of("services", properties.getServices().keySet().stream().sorted().toList());
    }

    /** 透传客户端请求头（含 Idempotency-Key 等），跳过 hop-by-hop 头避免冲突。 */
    private void copyRequestHeaders(HttpServletRequest request, org.springframework.http.HttpHeaders target) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (isHopByHop(name)) {
                continue;
            }
            String value = request.getHeader(name);
            if (value != null) {
                target.set(name, value);
            }
        }
    }

    private static boolean isHopByHop(String name) {
        return switch (name.toLowerCase()) {
            case "host", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade" -> true;
            default -> false;
        };
    }

    /**
     * 从完整匹配路径中剥离 {@code /proxy/{service}} 前缀，得到需要转发到上游的剩余路径。
     *
     * @param matched 请求在处理器内的匹配路径（如 {@code /proxy/catalog/skus}）
     * @param service 服务段（如 {@code catalog}）
     * @return 上游相对路径（如 {@code /skus}）；仅 {@code /proxy/{service}} 时返回空串
     */
    static String restPath(String matched, String service) {
        String prefix = "/proxy/" + service;
        if (matched == null) {
            return "";
        }
        return matched.startsWith(prefix) ? matched.substring(prefix.length()) : matched;
    }
}
