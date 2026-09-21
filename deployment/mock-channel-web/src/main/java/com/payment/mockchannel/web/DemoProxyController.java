package com.payment.mockchannel.web;

import com.payment.common.core.trace.TraceIdFilter;
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
            // 诊断③（034 plan）：demo 控制台 2s 轮询全走 GET——每次代理一行 INFO 是日志洪水；
            // GET 静默、非 GET（下单/支付等真实动作）降为 debug，保留排障能力又不刷屏
            if (method != HttpMethod.GET) {
                log.debug("[demo] proxy {} {} -> {}", method, request.getRequestURI(), target);
            }

            // 仅在确有请求体时才调 body()：RestClient 对 null body 会抛 NPE（GET/DELETE 无体请求）
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(target)
                    .headers(h -> copyRequestHeaders(request, h));
            if (body.length > 0) {
                spec.body(new String(body, StandardCharsets.UTF_8));
            }
            ResponseEntity<String> upstream = spec.retrieve().toEntity(String.class);
            // 透传上游 X-Trace-Id：演示控制台的日志行要靠它关联服务端日志（demo/trace-grep.sh <traceId>），
            // 重建响应时其余头维持最小集（Content-Type / Cache-Control），不放大暴露面
            ResponseEntity.BodyBuilder response = ResponseEntity.status(upstream.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    // 禁止浏览器缓存代理响应：reset 前后同一 URL 的数据会变（如空 SKU 列表），
                    // 缓存旧响应会让 /demo 永远显示过期状态
                    .header(HttpHeaders.CACHE_CONTROL, "no-store");
            String traceId = upstream.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
            if (traceId != null) {
                response.header(TraceIdFilter.TRACE_ID_HEADER, traceId);
            }
            return response.body(rewriteClientFacingPayUrl(upstream.getBody()));
        } catch (HttpStatusCodeException e) {
            // 异常响应同样透传 traceId：4xx/5xx（409 幂等、429 限流等）失败链路的日志关联不能断
            ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getStatusCode())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.APPLICATION_JSON);
            if (e.getResponseHeaders() != null) {
                String traceId = e.getResponseHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
                if (traceId != null) {
                    response.header(TraceIdFilter.TRACE_ID_HEADER, traceId);
                }
            }
            return response.body(e.getResponseBodyAsString());
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

    /**
     * 把上游响应里的容器内服务地址改写成<b>客户端可达</b>地址。
     *
     * <p>背景（2026-09-15 实测缺陷）：容器模式下 payment-service 的
     * {@code payment.mock-cashier.base-url} 曾配成 {@code http://mock-channel-web:8091}
     * （容器内服务名）。容器内访问确实 200，但该 URL 会被前端 {@code window.open()} 交给
     * <b>浏览器</b>打开，而浏览器所在宿主解析不了容器内网名字（NXDOMAIN）——
     * 表现为「下单成功、支付单也建了，但收银台跳转不了」。</p>
     *
     * <p>注意 {@code host.docker.internal} <b>不是</b>可用替代：该名仅在容器内可解析，
     * 宿主同样 NXDOMAIN（实测）。客户端口径下唯一正解是 {@code localhost}——
     * 端口已 publish 到宿主。</p>
     *
     * <p>修复分两层：①compose 已改配 {@code localhost}（根因）；②本方法作为「展示层兜底」
     * ——即使环境变量又被配错，/demo 页（唯一经此代理的入口）拿到的 {@code payUrl} 也一定可用。</p>
     */
    private String rewriteClientFacingPayUrl(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (body.contains("//mock-channel-web:8091")) {
            return body.replace("//mock-channel-web:8091", "//localhost:8091");
        }
        if (body.contains("//host.docker.internal:8091")) {
            return body.replace("//host.docker.internal:8091", "//localhost:8091");
        }
        return body;
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
