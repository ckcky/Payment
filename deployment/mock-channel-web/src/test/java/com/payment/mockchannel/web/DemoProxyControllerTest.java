package com.payment.mockchannel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payment.mockchannel.config.MockChannelProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 演示代理控制器测试：路径剥离的纯函数行为 + 转发后响应头的关键契约。
 *
 * <p>头透传用 JDK 内置 {@link HttpServer} 起一个内存"上游"验证：代理重建响应时不得丢失
 * 上游的 {@code X-Trace-Id}，否则演示控制台日志与服务端日志断链（日志抽屉捞不到 traceId）。</p>
 */
class DemoProxyControllerTest {

    @Test
    @DisplayName("带子路径：/proxy/catalog/skus → /skus")
    void stripsServicePrefixWithSubPath() {
        assertEquals("/skus", DemoProxyController.restPath("/proxy/catalog/skus", "catalog"));
    }

    @Test
    @DisplayName("带多级子路径：/proxy/order/orders/123/items → /orders/123/items")
    void stripsServicePrefixWithNestedPath() {
        assertEquals("/orders/123/items", DemoProxyController.restPath("/proxy/order/orders/123/items", "order"));
    }

    @Test
    @DisplayName("仅服务段：/proxy/payment → 空串（转发到上游根）")
    void stripsServicePrefixNoSubPath() {
        assertEquals("", DemoProxyController.restPath("/proxy/payment", "payment"));
    }

    @Test
    @DisplayName("null 匹配路径 → 空串，不抛异常")
    void nullMatchedReturnsEmpty() {
        assertEquals("", DemoProxyController.restPath(null, "catalog"));
    }

    @Test
    @DisplayName("非预期前缀原样返回（防御性，不崩溃）")
    void nonPrefixedReturnsAsIs() {
        assertEquals("/something/else", DemoProxyController.restPath("/something/else", "catalog"));
    }

    @Test
    @DisplayName("代理响应透传上游的 X-Trace-Id 头（正常分支）")
    void forwardsUpstreamTraceIdHeader() throws Exception {
        // 内存"上游"：回传固定 X-Trace-Id，模拟服务端 TraceIdFilter 在响应头回写 traceId 的行为
        HttpServer upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/skus", exchange -> {
            exchange.getResponseHeaders().set("X-Trace-Id", "trace-test-001");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try {
            MockChannelProperties properties = new MockChannelProperties();
            properties.setServices(Map.of("catalog",
                    "http://localhost:" + upstream.getAddress().getPort()));
            DemoProxyController controller = new DemoProxyController(properties);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/catalog/skus");
            request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/proxy/catalog/skus");

            ResponseEntity<String> response = controller.proxy("catalog", request);

            assertEquals(200, response.getStatusCode().value());
            assertEquals("trace-test-001", response.getHeaders().getFirst("X-Trace-Id"));
        } finally {
            upstream.stop(0);
        }
    }
}
