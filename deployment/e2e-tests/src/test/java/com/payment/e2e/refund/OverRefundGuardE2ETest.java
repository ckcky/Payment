package com.payment.e2e.refund;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Invariants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 / AC2（T419）：超退守卫 E2E——单次超付 / 多次累计超退 / 并发不超付。
 *
 * <p>资金安全第一优先级：任何时刻 DB 侧不变量「累计已退(含在途) ≤ 实付」必须恒真。</p>
 */
class OverRefundGuardE2ETest extends E2eBase {

    private final Db db = new Db();

    @Test
    void singleOverPaidRefundIsRejected() {
        runCase("over-refund-single", ctx -> {
            String uid = prefix("ovs");
            long skuId = skuWithPrice(ctx, 2500L); // 自建指定价格 SKU（live 库预置 SKU 价格不可假设）
            String orderNo = paidOrder(ctx, db, uid, uid, skuId, 1); // 2500

            Api.ApiResponse resp = API.refund(orderNo, null, 2501L, "e2e over single");
            ctx.response("refund-over", resp);
            assertThat(resp.status())
                    .as("单次超已付必须被拒 [order=%s, amount=2501 > paid=2500]，期望 4xx 实际 %d: %s",
                            orderNo, resp.status(), resp.body())
                    .isBetween(400, 499);
            Invariants.refundNotExceedPaid(db, orderNo);
            Invariants.businessNoChain(db, orderNo);
        });
    }

    @Test
    void cumulativeOverRefundIsRejected() {
        runCase("over-refund-cumulative", ctx -> {
            String uid = prefix("ovc");
            long skuId = skuWithPrice(ctx, 2500L);
            String orderNo = paidOrder(ctx, db, uid, uid, skuId, 2); // 5000

            Api.ApiResponse r1 = API.refund(orderNo, null, 3000L, "e2e cum #1");
            ctx.response("refund-1", r1);
            assertThat(r1.is2xx()).as("第一笔 3000 应受理 [order=%s]", orderNo).isTrue();
            awaitRefundStatus(r1.json().path("pmrf").asText(), "SUCCEEDED");

            Api.ApiResponse r2 = API.refund(orderNo, null, 2001L, "e2e cum #2"); // 3000+2001 > 5000
            ctx.response("refund-2", r2);
            assertThat(r2.status())
                    .as("累计超退必须被拒 [order=%s, 已退3000, 申请2001 > 剩余2000]，期望 4xx 实际 %d: %s",
                            orderNo, r2.status(), r2.body())
                    .isBetween(400, 499);

            Invariants.refundNotExceedPaid(db, orderNo);
        });
    }

    @Test
    void concurrentRefundsNeverExceedPaid() throws Exception {
        runCase("over-refund-concurrent", ctx -> {
            String uid = prefix("ovp");
            String orderNo = paidOrder(ctx, db, uid, uid, 1, 2); // 5000

            // 并发 4 笔 × 2000：受理侧悲观锁（refund_intake_locks）保证总额不超付；
            // 允许至多 2 笔成功（5000/2000），其余 4xx 拒绝，任何组合都不超付。
            List<Callable<Api.ApiResponse>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int seq = i;
                final long amount = 2000L;
                tasks.add(() -> API.refund(orderNo, null, amount, "e2e concurrent #" + seq));
            }
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<Api.ApiResponse>> futures;
            try {
                futures = pool.invokeAll(tasks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrent refunds interrupted", e);
            } finally {
                pool.shutdown();
            }
            int accepted = 0;
            for (int i = 0; i < futures.size(); i++) {
                Api.ApiResponse r;
                try {
                    r = futures.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("concurrent refund task failed", e.getCause() == null ? e : e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("concurrent refunds interrupted", e);
                }
                ctx.response("concurrent-" + i, r);
                if (r.is2xx()) {
                    accepted++;
                } else {
                    assertThat(r.status())
                            .as("并发拒绝必须为 4xx [order=%s]，实际 %d", orderNo, r.status())
                            .isBetween(400, 499);
                }
            }
            assertThat(accepted)
                    .as("并发受理笔数 ≤ 2（5000/2000）[order=%s]，实际 %d", orderNo, accepted)
                    .isLessThanOrEqualTo(2);

            // 等在途收敛后终验不变量（DB 侧恒真）
            Await.until("并发退款全部收敛（无 PROCESSING）[order=" + orderNo + "]", () -> {
                Object n = db.scalar("payment",
                        "SELECT COUNT(*) FROM refunds WHERE order_no='" + orderNo + "' AND status='PROCESSING'");
                return n != null && ((Number) n).longValue() == 0L;
            });
            Invariants.refundNotExceedPaid(db, orderNo);
            Invariants.businessNoChain(db, orderNo);
            ctx.invariant("concurrent: 4x2000 on paid=5000, accepted=" + accepted + ", invariant holds");
        });
    }
}
