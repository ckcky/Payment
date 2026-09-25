package com.payment.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 黑盒 HTTP 封装（spec 022 / T405，FR-003）：
 * JDK HttpClient 直连各服务，**4xx/5xx 原样返回**（不抛异常），供状态码断言。
 *
 * <p>覆盖全链路用例所需的全部端点：下单 / 建支付单 / 渠道回调 / 退款 / 退款回调 /
 * 状态查询 / 对账 / 审计四核对 / 结算门禁。</p>
 */
public final class Api {

    /** 原样响应：status 为 HTTP 状态码，body 为响应体（可为空串）。 */
    public record ApiResponse(int status, String body) {
        public boolean is2xx() {
            return status >= 200 && status < 300;
        }

        public JsonNode json() {
            try {
                return new ObjectMapper().readTree(body == null || body.isBlank() ? "{}" : body);
            } catch (Exception e) {
                throw new IllegalStateException("non-JSON body (status=" + status + "): " + body, e);
            }
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- 下单（两步式，spec 015/016）----

    /** POST /orders：建单（paymentNo=null）。 */
    public ApiResponse createOrder(String idempotencyKey, String userId, String merchantId,
                                   long skuId, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("merchantId", merchantId);
        body.put("items", java.util.List.of(Map.of("skuId", skuId, "quantity", quantity)));
        return post("order", "/orders", idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey), body);
    }

    /** POST /orders/{orderNo}/payments：显式选渠道建支付单。 */
    public ApiResponse createPayment(String orderNo, String channelCode) {
        return post("order", "/orders/" + orderNo + "/payments", Map.of(), Map.of("channelCode", channelCode));
    }

    /** GET /orders/{orderNo}。 */
    public ApiResponse getOrder(String orderNo) {
        return get("order", "/orders/" + orderNo);
    }

    // ---- 支付回调 / 收敛（payment-service）----

    /** POST /internal/payments/{paymentNo}/channel-callback：渠道回调（可重复发验幂等）。 */
    public ApiResponse paymentChannelCallback(String paymentNo, String status, String channelReference, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("channelReference", channelReference);
        body.put("reason", reason);
        return post("payment", "/internal/payments/" + paymentNo + "/channel-callback", Map.of(), body);
    }

    /** POST /payments/{ref}/resolve：UNKNOWN 人工收敛（管理端点，需 X-Admin-Token，F2）。 */
    public ApiResponse resolvePayment(String ref, String result, String channelReference, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        body.put("channelReference", channelReference);
        body.put("reason", reason);
        return post("payment", "/payments/" + ref + "/resolve",
                Map.of("X-Admin-Token", "demo-admin-token"), body);
    }

    /** GET /payments?paymentNo= （spec 041：废除「全数字即主键」双轨寻址）。 */
    public ApiResponse getPayment(String paymentNo) {
        return get("payment", "/payments?paymentNo=" + paymentNo);
    }

    // ---- 退款（order 发起 / payment 收敛，spec 019）----

    /** POST /internal/orders/refund：手工退款（order 收口）。 */
    public ApiResponse refund(String orderNo, String paymentNo, long amountMinor, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", orderNo);
        if (paymentNo != null) {
            body.put("paymentNo", paymentNo);
        }
        body.put("amountMinor", amountMinor);
        body.put("reason", reason);
        return post("order", "/internal/orders/refund", Map.of(), body);
    }

    /** GET /internal/payments/refunds/{refundNo}：PMRF 退款单状态（payment）。 */
    public ApiResponse getRefund(String pmrf) {
        return get("payment", "/internal/payments/refunds/" + pmrf);
    }

    /** POST /mock-channel/refund-callback：经 mock-channel 代理的退款渠道回调（可指定 status）。 */
    public ApiResponse refundCallback(String refundNo, String status, String channelReference, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refundNo", refundNo);
        body.put("status", status);
        body.put("channelReference", channelReference);
        body.put("reason", reason);
        return post("mock", "/mock-channel/refund-callback", Map.of(), body);
    }

    // ---- 会计四核对（audit，reconciliation；必须 LIVE 模式，spec 017/022 硬约束）----

    /** POST /internal/audit/batches：跑一批四核对（scope=LIVE）。 */
    public ApiResponse auditCreateBatch(String period, String scope, String triggeredBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", period);
        body.put("scope", scope);
        body.put("triggeredBy", triggeredBy);
        return post("reconciliation", "/internal/audit/batches", Map.of(), body);
    }

    /** POST /internal/reconciliation/statement-imports：导入渠道账单（032 实账化，SHA-256 内容幂等）。 */
    public ApiResponse statementImport(String channelCode, String period, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelCode", channelCode);
        body.put("period", period);
        body.put("sourceType", "FILE");
        body.put("content", content);
        body.put("importedBy", "e2e-csv");
        return post("reconciliation", "/internal/reconciliation/statement-imports", Map.of(), body);
    }

    public ApiResponse auditDifferences(String batchNo) {
        return get("reconciliation", "/internal/audit/batches/" + batchNo + "/differences");
    }

    public ApiResponse auditSuspend(String batchNo, long differenceId, String operator, String reason) {
        return post("reconciliation", "/internal/audit/batches/" + batchNo + "/differences/" + differenceId + "/suspend",
                Map.of(), Map.of("operator", operator, "reason", reason));
    }

    public ApiResponse auditAdjust(String batchNo, long differenceId, String kind, long amountMinor,
                                   String targetAccountCode, String operator, String reviewer, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("amountMinor", amountMinor);
        body.put("targetAccountCode", targetAccountCode);
        body.put("operator", operator);
        body.put("reviewer", reviewer);
        body.put("reason", reason);
        return post("reconciliation", "/internal/audit/batches/" + batchNo + "/differences/" + differenceId + "/adjust",
                Map.of(), body);
    }

    public ApiResponse auditRecheck(String batchNo) {
        return post("reconciliation", "/internal/audit/batches/" + batchNo + "/recheck", Map.of(), Map.of());
    }

    /** POST /internal/audit/batches/{batchNo}/close：存在未收口差异时 4xx（结算门禁语义）。 */
    public ApiResponse auditClose(String batchNo, String operator) {
        return post("reconciliation", "/internal/audit/batches/" + batchNo + "/close",
                Map.of(), Map.of("operator", operator));
    }

    public ApiResponse trialBalance() {
        return get("reconciliation", "/internal/audit/trial-balance");
    }

    public ApiResponse suspenseBalance() {
        return get("reconciliation", "/internal/audit/suspense-balance");
    }

    public ApiResponse settlementGate(String period) {
        return get("reconciliation", "/internal/audit/settlement-gate?period=" + period);
    }

    // ---- 商户（merchant，结算门禁用真实可结算商户）----

    /** POST /merchants：注册（PENDING_REVIEW）。 */
    public ApiResponse createMerchant(String code, String name, String settlementAccountRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("settlementAccountRef", settlementAccountRef);
        return post("merchant", "/merchants", Map.of(), body);
    }

    /** POST /merchants/{id}/approve：审核通过（ACTIVE + settlementEligible）。 */
    public ApiResponse approveMerchant(long id) {
        return post("merchant", "/merchants/" + id + "/approve", Map.of(), Map.of());
    }

    // ---- 结算（settlement）----

    /** POST /internal/settlements/batches：建结算批（受对账门禁约束）。 */
    public ApiResponse settlementCreateBatch(String merchantId, String period, String triggeredBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("period", period);
        body.put("triggeredBy", triggeredBy);
        return post("settlement", "/internal/settlements/batches", Map.of(), body);
    }

    // ---- 目录（catalog，造指定价格 SKU——请求级故障注入依赖金额尾数，T429）----

    /** POST /products。 */
    public ApiResponse createProduct(String productCode, String name, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productCode", productCode);
        body.put("name", name);
        body.put("type", type);
        return post("catalog", "/products", Map.of(), body);
    }

    /** POST /products/{id}/list：上架。 */
    public ApiResponse listProduct(long productId) {
        return post("catalog", "/products/" + productId + "/list", Map.of(), Map.of());
    }

    /** POST /skus。 */
    public ApiResponse createSku(String skuCode, long productId, String name, long priceMinor, String currencyCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skuCode", skuCode);
        body.put("productId", productId);
        body.put("name", name);
        body.put("priceMinor", priceMinor);
        body.put("currencyCode", currencyCode);
        body.put("deliveryDefinition", "e2e-auto-grant");
        return post("catalog", "/skus", Map.of(), body);
    }

    /** POST /skus/{id}/activate。 */
    public ApiResponse activateSku(long skuId) {
        return post("catalog", "/skus/" + skuId + "/activate", Map.of(), Map.of());
    }

    /** POST /internal/stock/seed：铺库存。 */
    public ApiResponse seedStock(long skuId, long total) {
        return post("catalog", "/internal/stock/seed", Map.of(), Map.of("skuId", skuId, "total", total));
    }

    // ---- 基础方法 ----

    /**
     * 按<b>绝对 URL</b> 发 GET（不是服务基址 + path）。
     *
     * <p>用途：验证「接口返回给客户端的链接本身是否可用」。{@code payUrl} 由服务端拼接、
     * 交给浏览器 {@code window.open()} 打开，因此它的可达性必须用「客户端视角」验证——
     * 若服务端误把容器内服务名（如 {@code http://mock-channel-web:8091}）填进去，
     * 容器内访问会 200 而宿主 NXDOMAIN，只有按绝对 URL 直连才能暴露（2026-09-15 实测缺陷）。</p>
     *
     * @param absoluteUrl 完整 URL（含 scheme 与 host）
     */
    public ApiResponse getAbsolute(String absoluteUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(absoluteUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(resp.statusCode(), resp.body());
        } catch (Exception e) {
            // 链接不可达（DNS 解析失败 / 连接拒绝）本身就是要断言的失败信号，不抛异常
            return new ApiResponse(0, "unreachable: " + e.getMessage());
        }
    }

    public ApiResponse get(String service, String path) {
        return exchange(service, "GET", path, Map.of(), null);
    }

    public ApiResponse post(String service, String path, Map<String, String> headers, Map<String, Object> body) {
        return exchange(service, "POST", path, headers, body);
    }

    private ApiResponse exchange(String service, String method, String path,
                                 Map<String, String> headers, Map<String, Object> body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(Env.serviceUrl(service) + path))
                    .timeout(Duration.ofSeconds(10));
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            } else {
                b.GET();
            }
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(resp.statusCode(), resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP " + method + " " + service + path + " failed: " + e.getMessage(), e);
        }
    }

    public Api() {
    }
}
