package com.payment.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.atomic.AtomicLong;

/**
 * E2E 用例基类（spec 022 / T423，FR-013 数据隔离）：
 * - 每用例唯一业务前缀 {@code e2e-<runId>-<case>}（userId/merchantId/幂等键均带前缀，断言按单号过滤）；
 * - {@link #runCase} 统一包装：失败自动 dump（FR-007），finally 落 invariants.log；
 * - 造单助手 {@link #paidOrder}：两步式下单 → 支付 → 等 PAID。
 */
public abstract class E2eBase {

    private static final AtomicLong SEQ = new AtomicLong();
    protected static final Api API = new Api();

    /** 本用例唯一前缀（userId / merchantId 用，保证跨用例数据隔离）。 */
    protected String prefix(String caseName) {
        return Env.runPrefix() + "-" + caseName + "-" + SEQ.incrementAndGet();
    }

    /** 用例包装：失败自动落盘诊断产物（响应体 / trace / invariants.log）。 */
    protected void runCase(String caseName, java.util.function.Consumer<Dump.Context> body) {
        Dump.Context ctx = Dump.forCase(caseName);
        try {
            body.accept(ctx);
        } catch (Throwable t) {
            ctx.invariant("FAILED: " + t);
            throw t;
        } finally {
            ctx.flush();
        }
    }

    /**
     * 造一单已支付订单（happy path 前置）：
     * POST /orders（建单）→ POST /orders/{no}/payments（同步渠道 SUCCESS）→ 轮询订单 PAID。
     *
     * @return orderNo
     */
    protected String paidOrder(Dump.Context ctx, Db db, String userId, String merchantId, long skuId, int qty) {
        String idem = userId + "-order";
        Api.ApiResponse created = API.createOrder(idem, userId, merchantId, skuId, qty);
        ctx.response("createOrder", created);
        if (!created.is2xx()) {
            throw new IllegalStateException("createOrder failed: HTTP " + created.status() + " " + created.body());
        }
        String orderNo = created.json().path("orderNo").asText();
        Api.ApiResponse paid = API.createPayment(orderNo, "ALIPAY");
        ctx.response("createPayment", paid);
        if (!paid.is2xx()) {
            throw new IllegalStateException("createPayment failed: HTTP " + paid.status() + " " + paid.body());
        }
        Await.until("订单收敛为 PAID [order=" + orderNo + "]", () -> {
            Api.ApiResponse resp = API.getOrder(orderNo);
            return resp.is2xx() && "PAID".equals(resp.json().path("status").asText());
        });
        return orderNo;
    }

    /**
     * 造一个指定价格（分）的可售 SKU：建品 → 建 SKU（价格带尾数，供请求级故障注入 T429）
     * → 上架 → 激活 → 铺库存。
     *
     * @return 新 SKU id
     */
    protected long skuWithPrice(Dump.Context ctx, long priceMinor) {
        String tag = Long.toString(System.currentTimeMillis(), 36) + "-" + SEQ.incrementAndGet();
        Api.ApiResponse product = API.createProduct("e2e-p-" + tag, "e2e product", "DIGITAL");
        ctx.response("createProduct", product);
        if (!product.is2xx()) {
            throw new IllegalStateException("createProduct failed: " + product.body());
        }
        long productId = product.json().path("id").asLong();
        API.listProduct(productId);

        Api.ApiResponse sku = API.createSku("e2e-sku-" + tag, productId, "e2e sku", priceMinor, "CNY");
        ctx.response("createSku", sku);
        if (!sku.is2xx()) {
            throw new IllegalStateException("createSku failed: " + sku.body());
        }
        long skuId = sku.json().path("id").asLong();
        API.activateSku(skuId);
        Api.ApiResponse seeded = API.seedStock(skuId, 100);
        ctx.response("seedStock", seeded);
        if (!seeded.is2xx()) {
            throw new IllegalStateException("seedStock failed: " + seeded.body());
        }
        return skuId;
    }

    /** 从订单详情取 paymentNo（生效支付单）。 */
    protected String paymentNoOf(String orderNo) {
        Api.ApiResponse resp = API.getOrder(orderNo);
        String paymentNo = resp.json().path("paymentNo").asText(null);
        if (paymentNo == null || paymentNo.isBlank() || "null".equals(paymentNo)) {
            throw new IllegalStateException("order has no effective paymentNo yet: " + orderNo);
        }
        return paymentNo;
    }

    /** 轮询 PMRF 退款单收敛到目标状态（payment 内部查询端点）。 */
    protected void awaitRefundStatus(String pmrf, String expected) {
        Await.until("退款单收敛 " + pmrf + " → " + expected, () -> {
            Api.ApiResponse resp = API.getRefund(pmrf);
            return resp.is2xx() && expected.equals(resp.json().path("status").asText());
        });
    }

    /** trace 快照按表落盘（失败诊断用）。 */
    protected void dumpTrace(Dump.Context ctx, String orderNo) {
        try {
            ctx.json("trace-" + orderNo, Trace.snapshot(orderNo));
        } catch (Exception e) {
            ctx.invariant("trace dump failed: " + e.getMessage());
        }
    }

    /** 读订单表行的便捷方法。 */
    protected JsonNode orderRow(Db db, String orderNo) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                db.query("order", "SELECT * FROM orders WHERE order_no='" + orderNo + "'"));
    }
}
