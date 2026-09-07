package com.payment.e2e.refund;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Invariants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 / AC1（T418）：退款主链 E2E——部分退款 + 全额退款。
 *
 * <p>验证 spec 019 两层退款单（TXRF→PMRF）全链路：受理 → 渠道异步回调 → 收口（订单状态 /
 * 交易 refunded_minor / 权益撤销 / 履约终止 / 记账平衡），并全链断言单号链与超退不变量。</p>
 */
class RefundChainE2ETest extends E2eBase {

    private final Db db = new Db();

    @Test
    void partialRefundChainKeepsAllStoresConsistent() {
        runCase("partial-refund-chain", ctx -> {
            String uid = prefix("prf");
            long unitPrice = 2500L;
            // 自建指定价格 SKU（live 库预置 SKU 价格不可假设，spec 022 live 实跑修正）
            long skuId = skuWithPrice(ctx, unitPrice);
            String orderNo = paidOrder(ctx, db, uid, uid, skuId, 2); // 总额 5000

            // 部分退款 1200
            Api.ApiResponse resp = API.refund(orderNo, null, 1200L, "e2e partial refund");
            ctx.response("refund", resp);
            assertThat(resp.is2xx())
                    .as("部分退款受理 [order=%s]，期望 2xx 实际 %d: %s", orderNo, resp.status(), resp.body())
                    .isTrue();
            String pmrf = resp.json().path("pmrf").asText();
            String txrf = resp.json().path("txrf").asText();
            assertThat(pmrf).startsWith("PMRF");
            assertThat(txrf).startsWith("TXRF");

            awaitRefundStatus(pmrf, "SUCCEEDED");

            // 收口收敛：订单 → PARTIALLY_REFUNDED，交易 refunded_minor == 1200
            Await.until("订单收敛 PARTIALLY_REFUNDED [order=" + orderNo + "]", () -> {
                Api.ApiResponse o = API.getOrder(orderNo);
                return o.is2xx() && "PARTIALLY_REFUNDED".equals(o.json().path("status").asText());
            });
            Object refunded = db.scalar("order",
                    "SELECT refunded_minor FROM transactions WHERE order_no='" + orderNo + "'");
            assertThat(refunded)
                    .as("交易累计已退 [order=%s, 表=order.transactions]，期望 1200 实际 %s", orderNo, refunded)
                    .isEqualTo(1200L);

            // 资金 / 单号 / 记账不变量
            Invariants.refundNotExceedPaid(db, orderNo);
            Invariants.businessNoChain(db, orderNo);
            Invariants.ledgerBalanced(db, orderNo);
            Invariants.noOrphanRows(db, orderNo);
            ctx.invariant("partial-refund: paid=5000 refunded=1200 status=PARTIALLY_REFUNDED txrf=" + txrf);
            dumpTrace(ctx, orderNo);
        });
    }

    @Test
    void fullRefundChainRevokesEntitlementsAndTerminatesFulfillments() {
        runCase("full-refund-chain", ctx -> {
            String uid = prefix("frf");
            long skuId = skuWithPrice(ctx, 2500L);
            String orderNo = paidOrder(ctx, db, uid, uid, skuId, 1); // 2500
            String paymentNo = paymentNoOf(db, orderNo);

            Api.ApiResponse resp = API.refund(orderNo, paymentNo, 2500L, "e2e full refund");
            ctx.response("refund", resp);
            assertThat(resp.is2xx())
                    .as("全额退款受理 [order=%s, payment=%s]，期望 2xx 实际 %d: %s",
                            orderNo, paymentNo, resp.status(), resp.body())
                    .isTrue();
            String pmrf = resp.json().path("pmrf").asText();
            awaitRefundStatus(pmrf, "SUCCEEDED");

            Await.until("订单收敛 REFUNDED [order=" + orderNo + "]", () -> {
                Api.ApiResponse o = API.getOrder(orderNo);
                return o.is2xx() && "REFUNDED".equals(o.json().path("status").asText());
            });

            // 后效齐全（AC1.3）：权益撤销 + 履约终止（异步链，轮询收敛）
            Await.until("权益全部非 AVAILABLE [order=" + orderNo + "]", () -> {
                Object n = db.scalar("entitlement",
                        "SELECT COUNT(*) FROM entitlements WHERE order_no='" + orderNo + "' AND status='AVAILABLE'");
                return n != null && ((Number) n).longValue() == 0L
                        && ((Number) db.scalar("entitlement",
                        "SELECT COUNT(*) FROM entitlements WHERE order_no='" + orderNo + "'")).longValue() > 0;
            });
            Await.until("履约无在途残留 [order=" + orderNo + "]", () -> {
                Object inFlight = db.scalar("fulfillment",
                        "SELECT COUNT(*) FROM fulfillments WHERE order_no='" + orderNo + "'"
                                + " AND status NOT IN ('CANCELLED','DELIVERED')");
                Object total = db.scalar("fulfillment",
                        "SELECT COUNT(*) FROM fulfillments WHERE order_no='" + orderNo + "'");
                // 撤销成功 → CANCELLED；已交付不可撤（设计语义）→ DELIVERED 保留
                return total != null && inFlight != null
                        && ((Number) total).longValue() > 0
                        && ((Number) inFlight).longValue() == 0;
            });

            Invariants.orderStatus(db, orderNo, "REFUNDED");
            Invariants.entitlementRevoked(db, orderNo);
            Invariants.fulfillmentTerminated(db, orderNo);
            Invariants.refundNotExceedPaid(db, orderNo);
            Invariants.businessNoChain(db, orderNo);
            Invariants.ledgerBalanced(db, orderNo);
            ctx.invariant("full-refund: paid=2500 refunded=2500 status=REFUNDED entitlements=REVOKED fulfillments=terminated");
            dumpTrace(ctx, orderNo);
        });
    }
}
