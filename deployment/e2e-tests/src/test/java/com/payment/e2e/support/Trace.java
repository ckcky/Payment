package com.payment.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 行级快照客户端（spec 022 / T408，FR-008）：
 * {@code GET /demo/trace?orderId=ORxxx}（mock-channel-web 8091）跨 9 库 14 表快照，
 * 返回 {@code sections[].{system, table, label, rows, error}}。
 */
public final class Trace {

    private static final Api API = new Api();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 拉取全量 trace 快照（原始 JSON）。 */
    public static JsonNode snapshot(String orderNo) {
        Api.ApiResponse resp = API.get("mock", "/demo/trace?orderId=" + orderNo);
        if (!resp.is2xx()) {
            throw new IllegalStateException("trace snapshot failed: HTTP " + resp.status() + " " + resp.body());
        }
        try {
            return MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("trace snapshot non-JSON: " + resp.body(), e);
        }
    }

    /** 按表名取某 section 的 rows（找不到返回 null）。 */
    public static JsonNode rows(String orderNo, String table) {
        JsonNode root = snapshot(orderNo);
        for (JsonNode section : root.path("sections")) {
            if (table.equalsIgnoreCase(section.path("table").asText())) {
                return section.path("rows");
            }
        }
        return null;
    }

    private Trace() {
    }
}
