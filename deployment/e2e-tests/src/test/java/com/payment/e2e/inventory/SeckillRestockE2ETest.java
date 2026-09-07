package com.payment.e2e.inventory;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Env;
import com.payment.e2e.support.Invariants;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 / AC5.6（T426）：秒杀回补 E2E——秒杀 SKU 全额退款后库存回补（available 归还），
 * 普通 SKU 不变。
 *
 * <p>秒杀 SKU 由 {@code e2e.seckill.sku-id}（e2e-local.properties）指定；未配置时按
 * Assumptions 跳过（NFR-005：不产生假红）。库存断言走 catalog.stock + Invariants.stockConserved。</p>
 */
class SeckillRestockE2ETest extends E2eBase {

    private final Db db = new Db();

    @Test
    void seckillSkuRestocksAfterFullRefundWhileNormalSkuDoesNot() {
        runCase("seckill-restock", ctx -> {
            String seckillSku = System.getProperty("e2e.seckill.sku-id",
                    systemPropertyFromPropertiesFile("e2e.seckill.sku-id"));
            Assumptions.assumeTrue(seckillSku != null && !seckillSku.isBlank(),
                    "e2e.seckill.sku-id 未配置，跳过秒杀回补用例（NFR-005 防假红）");
            long seckillSkuId = Long.parseLong(seckillSku.trim());

            // 普通 SKU（1 号）做对照
            String uid = prefix("sr");
            String normalOrder = paidOrder(ctx, db, uid + "-normal", uid, skuWithPrice(ctx, 2500L), 1);
            long normalAvailableBefore = availableOf(1);

            String seckillOrder = paidOrder(ctx, db, uid + "-seckill", uid, seckillSkuId, 1);
            long seckillAvailableBefore = availableOf(seckillSkuId);

            // 两单全额退款
            fullRefund(ctx, normalOrder);
            fullRefund(ctx, seckillOrder);

            // 普通 SKU：不回补（available 不变）；秒杀 SKU：available 归还
            Await.until("秒杀 SKU 库存回补 [sku=" + seckillSkuId + "]", () ->
                    availableOf(seckillSkuId) == seckillAvailableBefore);
            assertThat(availableOf(1))
                    .as("普通 SKU 退款不回补 [sku=1, 表=catalog.stock]，退款前 %d 实际 %d",
                            normalAvailableBefore, availableOf(1))
                    .isEqualTo(normalAvailableBefore);
            Invariants.stockConserved(db, 1);
            Invariants.stockConserved(db, seckillSkuId);
            ctx.invariant("seckill: sku " + seckillSkuId + " restocked after refund; normal sku unchanged");
        });
    }

    private long availableOf(long skuId) {
        Object v = db.scalar("catalog", "SELECT available FROM stock WHERE sku_id=" + skuId);
        return v == null ? -1L : ((Number) v).longValue();
    }

    private void fullRefund(Dump.Context ctx, String orderNo) {
        Api.ApiResponse o = API.getOrder(orderNo);
        long total = o.json().path("totalMinor").asLong();
        Api.ApiResponse resp = API.refund(orderNo, null, total, "e2e seckill restock");
        ctx.response("refund-" + orderNo, resp);
        assertThat(resp.is2xx()).as("全额退款受理 [order=%s]", orderNo).isTrue();
        awaitRefundStatus(resp.json().path("pmrf").asText(), "SUCCEEDED");
    }

    /** 从 classpath e2e-<env>.properties 读一个键（Env 之外的低频旁路读取）。 */
    private String systemPropertyFromPropertiesFile(String key) {
        try (var in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("e2e-" + Env.env() + ".properties")) {
            if (in == null) {
                return null;
            }
            var props = new java.util.Properties();
            props.load(in);
            return props.getProperty(key);
        } catch (Exception e) {
            return null;
        }
    }
}
