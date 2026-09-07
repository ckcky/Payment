package com.payment.e2e.numbering;

import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Invariants;
import com.payment.e2e.support.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 / AC4（T422）：业务单号链 E2E——前缀规范、雪花唯一、双号互记、跨库可追溯无孤儿。
 */
class BusinessNoChainE2ETest extends E2eBase {

    private final Db db = new Db();

    @Test
    void fullChainNumberingIsTraceableAcrossStores() {
        runCase("no-chain", ctx -> {
            String uid = prefix("num");
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);

            // 全额退款 → 触发 TXRF/PMRF/REFUND attempt 全链
            var resp = API.refund(orderNo, paymentNo, 2500L, "e2e numbering");
            ctx.response("refund", resp);
            assertThat(resp.is2xx()).isTrue();
            awaitRefundStatus(resp.json().path("pmrf").asText(), "SUCCEEDED");

            // 单号链不变量（前缀 + 双号互记 + attempt 归属 + 跨库一致）
            Invariants.businessNoChain(db, orderNo);
            Invariants.noOrphanRows(db, orderNo);

            // OR / PM 长度：前缀 2 + 雪花（ADR-0062/0063）
            String txNo = String.valueOf(db.scalar("order",
                    "SELECT transaction_no FROM transactions WHERE order_no='" + orderNo + "'"));
            assertThat(orderNo).startsWith("OR").hasSizeGreaterThanOrEqualTo(10);
            assertThat(txNo).startsWith("TX").hasSizeGreaterThanOrEqualTo(10);
            assertThat(paymentNo).startsWith("PM").hasSizeGreaterThanOrEqualTo(10);

            // trace 快照（FR-008）：mock-channel /demo/trace 跨库 sections 覆盖关键表
            JsonNode snapshot = Trace.snapshot(orderNo);
            ctx.json("trace", snapshot);
            assertThat(snapshot.path("sections").isArray()
                    && snapshot.path("sections").size() > 0)
                    .as("trace 快照含 sections [order=%s]", orderNo).isTrue();

            ctx.invariant("numbering: OR/TX/PM/TXRF/PMRF prefixes + cross-store mutual references verified");
            dumpTrace(ctx, orderNo);
        });
    }
}
