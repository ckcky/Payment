package com.payment.mockchannel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 演示代理路径剥离的纯函数测试：确保 {@code /proxy/{service}} 前缀被正确移除，
 * 转发到上游时路径正确（不越界、不丢前导斜杠）。
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
}
