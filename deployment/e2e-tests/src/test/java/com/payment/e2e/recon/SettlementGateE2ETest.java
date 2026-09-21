package com.payment.e2e.recon;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 / AC3.3 独立用例（T427）：结算门禁——对账存在未收口差异时不允许结算。
 */
class SettlementGateE2ETest extends E2eBase {

    private final Db db = new Db();

    @Test
    void settlementRejectedWhileDifferenceUnresolved() {
        runCase("settlement-gate", ctx -> {
            String uid = prefix("gate");

            // 1) 先建一笔有差异的审计批：注入平衡的孤儿 posting（不还原，门禁验证后再清理）
            //    031 口径：postings 带 event_type/period/posted_at；分录只挂账户实例
            //    （7=CHANNEL_RECEIVABLE:ALIPAY、2=MERCHANT_PAYABLE:LEGACY），entry_type/source_* 停写。
            db.execute("ledger", "INSERT INTO postings (posting_no, event_type, idempotency_key, source_type,"
                    + " source_id, status, currency, period, posted_at, created_at, updated_at, version)"
                    + " VALUES ('LPe2e-gate-" + uid + "', 'PAYMENT_CAPTURE', 'PAYMENT_CAPTURE:e2e-gate-" + uid + "',"
                    + " 'PAYMENT', 'e2e-gate-" + uid + "',"
                    + " 'POSTED', 'CNY', DATE_FORMAT(NOW(), '%Y-%m'), NOW(), NOW(), NOW(), 1)");
            long pid = ((Number) db.scalar("ledger",
                    "SELECT id FROM postings WHERE posting_no='LPe2e-gate-" + uid + "'")).longValue();
            db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                    + " currency, created_at) VALUES (" + pid + ", 7, 'DEBIT', 66, 'CNY', NOW())");
            db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                    + " currency, created_at) VALUES (" + pid + ", 2, 'CREDIT', 66, 'CNY', NOW())");
            try {
                String period = "e2e-gate-" + Long.toString(System.currentTimeMillis(), 36);
                Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e");
                ctx.response("auditCreateBatch", batch);
                assertThat(batch.is2xx()).isTrue();
                String batchNo = batch.json().path("batchNo").asText();
                Await.until("审计批次结算 [batch=" + batchNo + "]", () -> {
                    Object status = db.scalar("reconciliation",
                            "SELECT status FROM audit_batches WHERE batch_no='" + batchNo + "'");
                    return status != null && !"PROCESSING".equals(String.valueOf(status))
                            && !"RECHECKING".equals(String.valueOf(status));
                });

                // 2) 未收口差异在挂 → close 拒绝（400）
                Api.ApiResponse closed = API.auditClose(batchNo, "e2e-operator");
                ctx.response("close-with-pending", closed);
                assertThat(closed.status())
                        .as("存在未收口差异时 close 必须被拒 [batch=%s]，期望 4xx 实际 %d: %s",
                                batchNo, closed.status(), closed.body())
                        .isBetween(400, 499);

                // 3) 同期结算建批被门禁拒绝（AC3.3）——门禁在商户校验之后，
                //    需真实可结算商户（settlement.createBatch 以 Long 解析商户号）
                Api.ApiResponse merchant = API.createMerchant("e2e-" + uid, "e2e merchant", "e2e-acct-" + uid);
                ctx.response("createMerchant", merchant);
                assertThat(merchant.is2xx()).as("注册商户").isTrue();
                long merchantId = merchant.json().path("id").asLong();
                Api.ApiResponse approved = API.approveMerchant(merchantId);
                ctx.response("approveMerchant", approved);
                assertThat(approved.is2xx()).as("审核通过（ACTIVE + 可结算）").isTrue();

                Api.ApiResponse settlement = API.settlementCreateBatch(String.valueOf(merchantId), period, "e2e");
                ctx.response("settlementCreateBatch", settlement);
                assertThat(settlement.status())
                        .as("存在未收口差异时结算必须被拒 [period=%s]，期望 4xx 实际 %d: %s",
                                period, settlement.status(), settlement.body())
                        .isBetween(400, 499);
            } finally {
                // 还原注入（门禁验证完毕；031：分录追溯走 posting_id）
                db.execute("ledger", "DELETE FROM ledger_entries WHERE posting_id = " + pid);
                db.execute("ledger", "DELETE FROM postings WHERE posting_no='LPe2e-gate-" + uid + "'");
            }
            ctx.invariant("gate: pending difference blocks close(4xx) and settlement(4xx)");
        });
    }
}
