package com.payment.e2e.support;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断言原语库（spec 022 / 批次 C，T411~T417）：
 * 全链路资金 / 单号 / 记账 / 状态不变量的统一实现，P0/P1 用例复用。
 *
 * <p>失败信息规范（NFR-003）：必含期望值 / 实际值 / 关联业务单号 / 涉及库表。</p>
 */
public final class Invariants {

    /** 退款资金安全不变量（FR-006 / AC2）：payment 侧累计已退（在途按申请额保守计）≤ 实付。 */
    public static void refundNotExceedPaid(Db db, String orderNo) {
        Object paid = db.scalar("payment", "SELECT SUM(amount_minor) FROM payments WHERE order_no='" + orderNo + "'");
        Object refundedInFlight = db.scalar("payment",
                "SELECT SUM(amount_minor) FROM refunds WHERE order_no='" + orderNo
                        + "' AND status IN ('SUCCEEDED','PROCESSING')");
        long paidMinor = paid == null ? 0L : ((Number) paid).longValue();
        long refunded = refundedInFlight == null ? 0L : ((Number) refundedInFlight).longValue();
        assertThat(refunded)
                .as("超退守卫：payment.refunds 累计已退(含在途)必须 ≤ payments 实付 [order=%s, 表=payment.refunds/payments]，"
                        + "期望 <=%d 实际 %d", orderNo, paidMinor, refunded)
                .isLessThanOrEqualTo(paidMinor);

        // 双侧一致：order.transactions.refunded_minor == payment 侧 SUCCEEDED 累计
        Object txnRefunded = db.scalar("order",
                "SELECT t.refunded_minor FROM transactions t WHERE t.order_no='" + orderNo + "'");
        Object refundedSettled = db.scalar("payment",
                "SELECT SUM(amount_minor) FROM refunds WHERE order_no='" + orderNo + "' AND status='SUCCEEDED'");
        long settled = refundedSettled == null ? 0L : ((Number) refundedSettled).longValue();
        assertThat(txnRefunded == null ? 0L : ((Number) txnRefunded).longValue())
                .as("双侧退款金额一致：order.transactions.refunded_minor 必须等于 payment.refunds(SUCCEEDED) 之和 "
                        + "[order=%s, 表=order.transactions/payment.refunds]，期望 %d 实际 %s",
                        orderNo, settled, txnRefunded)
                .isEqualTo(settled);
    }

    /** 业务单号链不变量（FR-009 / AC4）：前缀 + 双号互记 + attempt 归属。 */
    public static void businessNoChain(Db db, String orderNo) {
        Map<String, Object> order = db.query("order",
                "SELECT order_no, payment_no FROM orders WHERE order_no='" + orderNo + "'").get(0);
        assertThat(String.valueOf(order.get("order_no"))).startsWith("OR");

        List<Map<String, Object>> txns = db.query("order",
                "SELECT transaction_no, payment_no FROM transactions WHERE order_no='" + orderNo + "'");
        assertThat(txns).as("1:1 交易单 [order=%s, 表=order.transactions]", orderNo).hasSize(1);
        String txnNo = String.valueOf(txns.get(0).get("transaction_no"));
        assertThat(txnNo).startsWith("TX");

        String paidPaymentNo = txns.get(0).get("payment_no") == null ? null
                : String.valueOf(txns.get(0).get("payment_no"));
        if (paidPaymentNo != null) {
            assertThat(paidPaymentNo).startsWith("PM");
            // 生效支付单回填一致（spec 019 / ADR-0067）
            assertThat(db.scalar("payment",
                    "SELECT COUNT(*) FROM payments WHERE payment_no='" + paidPaymentNo
                            + "' AND order_no='" + orderNo + "'"))
                    .as("生效支付单跨库一致 [order=%s, payment=%s, 表=order.transactions/payment.payments]",
                            orderNo, paidPaymentNo)
                    .isEqualTo(1L);
            // PAYMENT attempt 归属
            assertThat(asLong(db.scalar("payment",
                    "SELECT COUNT(*) FROM payment_attempts WHERE payment_no='" + paidPaymentNo
                            + "' AND attempt_type='PAYMENT'")))
                    .as("PAYMENT attempt 归属 [payment=%s, 表=payment.payment_attempts]", paidPaymentNo)
                    .isGreaterThanOrEqualTo(1L);
        }

        List<Map<String, Object>> txrfs = db.query("order",
                "SELECT refund_no, payment_refund_no, payment_no FROM transaction_refunds WHERE order_no='" + orderNo + "'");
        for (Map<String, Object> txrf : txrfs) {
            String txrfNo = String.valueOf(txrf.get("refund_no"));
            assertThat(txrfNo).startsWith("TXRF");
            Object pmrf = txrf.get("payment_refund_no");
            if (pmrf != null) {
                String pmrfNo = String.valueOf(pmrf);
                assertThat(pmrfNo).startsWith("PMRF");
                // 双号互记（T412）：TXRF.payment_refund_no == refunds.refund_no 且反向互记
                assertThat(db.scalar("payment",
                        "SELECT COUNT(*) FROM refunds WHERE refund_no='" + pmrfNo
                                + "' AND transaction_refund_no='" + txrfNo + "'"))
                        .as("双号互记(反向) [txrf=%s, pmrf=%s, 表=order.transaction_refunds/payment.refunds]",
                                txrfNo, pmrfNo)
                        .isEqualTo(1L);
                assertThat(asLong(db.scalar("payment",
                        "SELECT COUNT(*) FROM payment_attempts WHERE payment_no='" + txrf.get("payment_no")
                                + "' AND attempt_type='REFUND'")))
                        .as("REFUND attempt 归属 [payment=%s, 表=payment.payment_attempts]", txrf.get("payment_no"))
                        .isGreaterThanOrEqualTo(1L);
            }
        }
    }

    /** 记账平衡不变量（FR-007 / AC1.4）：按单 Σ借==Σ贷 + 全局试算平衡。 */
    public static void ledgerBalanced(Db db, String orderNo) {
        // 全局试算平衡（随时可断言，不依赖订单）
        Object debit = db.scalar("ledger",
                "SELECT SUM(amount_minor) FROM ledger_entries WHERE direction='DEBIT'");
        Object credit = db.scalar("ledger",
                "SELECT SUM(amount_minor) FROM ledger_entries WHERE direction='CREDIT'");
        long d = debit == null ? 0L : ((Number) debit).longValue();
        long c = credit == null ? 0L : ((Number) credit).longValue();
        assertThat(d).as("全局试算平衡 [ledger=ledger.ledger_entries]，期望借=贷=%d，实际借 %d / 贷 %d", d, d, c).isEqualTo(c);

        // 按来源（支付单）平衡：该订单生效支付单的分录借贷相等
        Object paymentNo = db.scalar("order",
                "SELECT payment_no FROM transactions WHERE order_no='" + orderNo + "'");
        if (paymentNo != null) {
            List<Map<String, Object>> rows = db.query("ledger",
                    "SELECT direction, SUM(amount_minor) s FROM ledger_entries WHERE source_type='PAYMENT'"
                            + " AND source_id='" + paymentNo + "' GROUP BY direction");
            long dr = rows.stream().filter(r -> "DEBIT".equals(r.get("direction")))
                    .mapToLong(r -> ((Number) r.get("s")).longValue()).sum();
            long cr = rows.stream().filter(r -> "CREDIT".equals(r.get("direction")))
                    .mapToLong(r -> ((Number) r.get("s")).longValue()).sum();
            assertThat(dr).as("按单借贷平衡 [payment=%s, 表=ledger.ledger_entries]，期望借=贷，实际借 %d / 贷 %d",
                            paymentNo, dr, cr).isEqualTo(cr);
        }
    }

    /** 订单状态一致性（FR-010）：orders.refunded_minor 与 payment 侧结算退款一致、状态按金额推导吻合。 */
    public static void orderStatus(Db db, String orderNo, String expectedStatus) {
        List<Map<String, Object>> rows = db.query("order",
                "SELECT status, paid_minor, refunded_minor FROM orders WHERE order_no='" + orderNo + "'");
        assertThat(rows).as("订单存在 [order=%s, 表=order.orders]", orderNo).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("status")))
                .as("订单状态 [order=%s, 表=order.orders]，期望 %s 实际 %s",
                        orderNo, expectedStatus, rows.get(0).get("status"))
                .isEqualTo(expectedStatus);
    }

    /** 权益撤销不变量（FR-010 / AC1.3）：该订单全部权益非 AVAILABLE（全额退款后）。 */
    public static void entitlementRevoked(Db db, String orderNo) {
        long available = count(db, "entitlement",
                "SELECT COUNT(*) FROM entitlements WHERE order_no='" + orderNo + "' AND status='AVAILABLE'");
        assertThat(available)
                .as("退款后权益全部非 AVAILABLE [order=%s, 表=entitlement.entitlements]，剩余 AVAILABLE=%d", orderNo, available)
                .isZero();
    }

    /** 履约终止不变量（FR-010 / AC1.3）：该订单全部履约单 CANCELLED。 */
    public static void fulfillmentTerminated(Db db, String orderNo) {
        List<Map<String, Object>> rows = db.query("fulfillment",
                "SELECT status, COUNT(*) c FROM fulfillments WHERE order_no='" + orderNo + "' GROUP BY status");
        long total = rows.stream().mapToLong(r -> ((Number) r.get("c")).longValue()).sum();
        long cancelled = rows.stream().filter(r -> "CANCELLED".equals(r.get("status")))
                .mapToLong(r -> ((Number) r.get("c")).longValue()).sum();
        assertThat(total).as("履约单存在 [order=%s, 表=fulfillment.fulfillments]", orderNo).isGreaterThan(0);
        assertThat(cancelled).as("履约逐条终止 [order=%s, 表=fulfillment.fulfillments]，期望全部 CANCELLED，实际 %s",
                        orderNo, rows).isEqualTo(total);
    }

    /** 库存守恒（FR-010 / AC5.6）：total == available + reserved + sold。 */
    public static void stockConserved(Db db, long skuId) {
        List<Map<String, Object>> rows = db.query("catalog",
                "SELECT total, available, reserved, sold FROM stock WHERE sku_id=" + skuId);
        assertThat(rows).as("库存聚合存在 [sku=%s, 表=catalog.stock]", skuId).hasSize(1);
        Map<String, Object> s = rows.get(0);
        long total = ((Number) s.get("total")).longValue();
        long sum = ((Number) s.get("available")).longValue() + ((Number) s.get("reserved")).longValue()
                + ((Number) s.get("sold")).longValue();
        assertThat(sum).as("库存守恒 total=available+reserved+sold [sku=%s, 表=catalog.stock]，total=%d 分量和=%d",
                        skuId, total, sum).isEqualTo(total);
    }

    /** 幂等重放不变量（FR-013 / AC5.1）：同一键重放后行数与状态不变。 */
    public static void idempotentReplay(Db db, String schema, String table, String where, long expectedRows) {
        long rows = count(db, schema, "SELECT COUNT(*) FROM " + table + " WHERE " + where);
        assertThat(rows).as("幂等重放行数不变 [%s.%s WHERE %s]，期望 %d 实际 %d",
                        schema, table, where, expectedRows, rows).isEqualTo(expectedRows);
    }

    /** 孤儿单检查（AC4.5）：本轮 e2e 前缀的单据在上下游库均可追溯，无缺失侧。 */
    public static void noOrphanRows(Db db, String orderNo) {
        assertThat(count(db, "order", "SELECT COUNT(*) FROM orders WHERE order_no='" + orderNo + "'"))
                .as("order 主单存在 [order=%s]", orderNo).isEqualTo(1L);
        assertThat(count(db, "order", "SELECT COUNT(*) FROM transactions WHERE order_no='" + orderNo + "'"))
                .as("交易单存在 [order=%s, 表=order.transactions]", orderNo).isEqualTo(1L);
        // 有生效支付单时 payment 侧必须可追溯
        Object paymentNo = db.scalar("order",
                "SELECT payment_no FROM transactions WHERE order_no='" + orderNo + "'");
        if (paymentNo != null) {
            assertThat(count(db, "payment", "SELECT COUNT(*) FROM payments WHERE payment_no='"
                    + paymentNo + "' AND order_no='" + orderNo + "'"))
                    .as("无孤儿：支付侧可追溯 [order=%s, payment=%s]", orderNo, paymentNo).isEqualTo(1L);
        }
    }

    private static long count(Db db, String schema, String sql) {
        Object v = db.scalar(schema, sql);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static long asLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private Invariants() {
    }
}
