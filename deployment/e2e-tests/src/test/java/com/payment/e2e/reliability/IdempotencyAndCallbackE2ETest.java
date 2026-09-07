package com.payment.e2e.reliability;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Invariants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 / AC5（T424 + T425）：幂等与回调异常路径 E2E。
 *
 * <ul>
 *   <li>AC5.1 同 Idempotency-Key 重复下单 → 同单吸收；</li>
 *   <li>AC5.3 渠道回调重复 3 次 → 终态吸收、后处理不重复；</li>
 *   <li>AC5.4 回调丢失 → UNKNOWN → resolve 收敛后后处理不丢（订单 PAID / 记账补齐）；</li>
 *   <li>AC5.5 回调乱序不回退终态。</li>
 * </ul>
 */
class IdempotencyAndCallbackE2ETest extends E2eBase {

    private final Db db = new Db();

    @Test
    void duplicateOrderWithSameIdempotencyKeyIsAbsorbed() {
        runCase("idem-order", ctx -> {
            String uid = prefix("ido");

            Api.ApiResponse first = API.createOrder(uid + "-key", uid, uid, 1, 1);
            ctx.response("order-1", first);
            assertThat(first.is2xx()).isTrue();
            String orderNo = first.json().path("orderNo").asText();

            Api.ApiResponse replay = API.createOrder(uid + "-key", uid, uid, 1, 1);
            ctx.response("order-2", replay);
            assertThat(replay.is2xx()).as("同键重放应 2xx 吸收").isTrue();
            assertThat(replay.json().path("orderNo").asText())
                    .as("同键重放返回同一订单 [key=%s]", uid + "-key")
                    .isEqualTo(orderNo);
            Invariants.idempotentReplay(db, "order", "orders", "order_no='" + orderNo + "'", 1);
        });
    }

    @Test
    void triplicateChannelCallbackIsAbsorbed() {
        runCase("idem-callback", ctx -> {
            String uid = prefix("idc");
            String orderNo = paidOrder(ctx, db, uid, uid, 1, 1);
            String paymentNo = paymentNoOf(orderNo);

            // 同一支付单重复回调 3 次：终态吸收、不重复后处理（记账/订单回写）
            for (int i = 0; i < 3; i++) {
                Api.ApiResponse cb = API.paymentChannelCallback(paymentNo, "SUCCESS",
                        "e2e-dup-cb-" + i, null);
                ctx.response("callback-" + i, cb);
                // 回调端点 2xx（吸收）或 4xx（终态冲突快速失败）都属幂等正确行为，不允许 5xx
                assertThat(cb.status())
                        .as("重复回调 #%d 不得 5xx [payment=%s]，实际 %d: %s", i, paymentNo, cb.status(), cb.body())
                        .isLessThan(500);
            }
            Invariants.orderStatus(db, orderNo, "PAID");
            Invariants.ledgerBalanced(db, orderNo);
            // 记账幂等：该支付单只应有一组 capture posting
            long postingCount = ((Number) db.scalar("ledger",
                    "SELECT COUNT(DISTINCT posting_id) FROM ledger_entries"
                            + " WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'"
                            + " AND entry_type='PAYMENT_CAPTURE'")).longValue();
            assertThat(postingCount)
                    .as("重复回调不重复记账 [payment=%s, 表=ledger.ledger_entries]，实际 capture posting=%d",
                            paymentNo, postingCount)
                    .isEqualTo(1L);
        });
    }

    @Test
    void lostCallbackConvergesViaResolveWithoutLosingPostProcessing() {
        runCase("cb-loss-resolve", ctx -> {
            String uid = prefix("unr");
            String idem = uid + "-order";

            // 请求级确定性触发（T429）：造尾数 12 价格的 SKU → 渠道无结论 → 回调丢失 → UNKNOWN
            long skuId = skuWithPrice(ctx, 2512L);
            Api.ApiResponse created = API.createOrder(idem, uid, uid, skuId, 1);
            ctx.response("createOrder", created);
            assertThat(created.is2xx()).isTrue();
            String orderNo = created.json().path("orderNo").asText();
            Api.ApiResponse paid = API.createPayment(orderNo, "ALIPAY");
            ctx.response("createPayment", paid);
            assertThat(paid.is2xx()).isTrue();

            // 回调丢失 → 支付 UNKNOWN、订单未支付
            Await.until("支付收敛 UNKNOWN [order=" + orderNo + "]", () -> {
                Api.ApiResponse p = API.getPayment(paymentNoOf(orderNo));
                return p.is2xx() && "UNKNOWN".equals(p.json().path("status").asText());
            });
            String paymentNo = paymentNoOf(orderNo);

            // 人工收敛
            Api.ApiResponse resolved = API.resolvePayment(paymentNo, "SUCCESS", "e2e-resolve-ref", "e2e resolve");
            ctx.response("resolve", resolved);
            assertThat(resolved.is2xx()).as("resolve 收敛 [payment=%s]", paymentNo).isTrue();

            // 后处理不丢：订单 PAID + 记账补齐 + 履约驱动
            Await.until("订单收敛 PAID（resolve 后处理补齐）[order=" + orderNo + "]", () -> {
                Api.ApiResponse o = API.getOrder(orderNo);
                return o.is2xx() && "PAID".equals(o.json().path("status").asText());
            });
            long capture = ((Number) db.scalar("ledger",
                    "SELECT COUNT(DISTINCT posting_id) FROM ledger_entries"
                            + " WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'"
                            + " AND entry_type='PAYMENT_CAPTURE'")).longValue();
            assertThat(capture)
                    .as("resolve 后记账补齐 [payment=%s, 表=ledger.ledger_entries]", paymentNo)
                    .isGreaterThanOrEqualTo(1L);
            Invariants.orderStatus(db, orderNo, "PAID");
            Invariants.ledgerBalanced(db, orderNo);
            Invariants.businessNoChain(db, orderNo);
        });
    }

    @Test
    void outOfOrderCallbackDoesNotRegressTerminalState() {
        runCase("cb-out-of-order", ctx -> {
            String uid = prefix("ooo");
            String orderNo = paidOrder(ctx, db, uid, uid, 1, 1);
            String paymentNo = paymentNoOf(orderNo);

            // 终态（SUCCEEDED）后补发「过期」的 UNKNOWN 回调：终态必须吸收、不回退
            Api.ApiResponse stale = API.paymentChannelCallback(paymentNo, "UNKNOWN",
                    "e2e-stale-cb", "late unknown callback");
            ctx.response("stale-callback", stale);
            assertThat(stale.status())
                    .as("过期 UNKNOWN 回调不得 5xx [payment=%s]，实际 %d", paymentNo, stale.status())
                    .isLessThan(500);

            Api.ApiResponse p = API.getPayment(paymentNo);
            assertThat(p.json().path("status").asText())
                    .as("终态不回退 [payment=%s, 表=payment.payments]，期望 SUCCEEDED 实际 %s",
                            paymentNo, p.json().path("status").asText())
                    .isEqualTo("SUCCEEDED");
            Invariants.orderStatus(db, orderNo, "PAID");
        });
    }
}
