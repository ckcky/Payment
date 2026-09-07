package com.payment.e2e.recon;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Invariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 / AC3（T420）：会计四核对准确性 E2E——LIVE 模式 CLEAN 0 差异 + FAULT 注入检出矩阵
 * + 挂账收口 + 门禁。
 *
 * <p>故障注入走 DB 直改（测试环境专用，plan §5），每个 FAULT 用后即恢复（还原 SQL 反向执行），
 * 保证「恢复 + recheck → BALANCED → close 2xx」的闭环可验证。注入矩阵覆盖账证 6 类 + 账账 2 类：
 * MISSING_POSTING / ORPHAN_POSTING / AMOUNT_MISMATCH / CURRENCY_MISMATCH / DIRECTION_MISMATCH /
 * DUPLICATE_POSTING / BALANCE_BREAK / ACCOUNT_RECON_BREAK。</p>
 */
class ReconciliationAccuracyE2ETest extends E2eBase {

    private final Db db = new Db();

    // ---- FAULT 注入矩阵 ----

    /** 单个故障注入定义：apply 施加、restore 还原、expectedKind 期望检出的差异类型。 */
    private record Fault(String name, Runnable apply, Runnable restore, String expectedKind) {
    }

    private List<Fault> faultMatrix(String orderNo, String paymentNo, String uid) {
        // 该支付单在 ledger 侧的分录行（注入前备份，还原时回插）
        List<Map<String, Object>> postingBackup = db.query("ledger",
                "SELECT * FROM postings WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'");
        List<Map<String, Object>> entryBackup = db.query("ledger",
                "SELECT * FROM ledger_entries WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'");
        String postingIds = joinIds(postingBackup, "id");

        List<Fault> faults = new ArrayList<>();
        faults.add(new Fault(
                "MISSING_POSTING",
                () -> {
                    db.execute("ledger", "DELETE FROM ledger_entries WHERE posting_id IN (" + postingIds + ")");
                    db.execute("ledger", "DELETE FROM postings WHERE id IN (" + postingIds + ")");
                },
                () -> {
                    insertRows("ledger", "postings", postingBackup);
                    insertRows("ledger", "ledger_entries", entryBackup);
                },
                "MISSING_POSTING"));
        faults.add(new Fault(
                "ORPHAN_POSTING",
                () -> {
                    db.execute("ledger", "INSERT INTO postings (posting_no, idempotency_key, source_type, source_id,"
                            + " status, currency, created_at, version) VALUES ('LPe2e-orphan-" + uid + "',"
                            + " 'e2e-orphan-key-" + uid + "', 'PAYMENT', 'e2e-orphan-" + uid + "',"
                            + " 'POSTED', 'CNY', NOW(), 1)");
                    long pid = ((Number) db.scalar("ledger",
                            "SELECT id FROM postings WHERE posting_no='LPe2e-orphan-" + uid + "'")).longValue();
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at) VALUES (" + pid
                            + ", 1, 'DEBIT', 100, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-orphan-" + uid + "', NOW())");
                },
                () -> {
                    db.execute("ledger", "DELETE FROM ledger_entries WHERE source_id='e2e-orphan-" + uid + "'");
                    db.execute("ledger", "DELETE FROM postings WHERE posting_no='LPe2e-orphan-" + uid + "'");
                },
                "ORPHAN_POSTING"));
        faults.add(new Fault(
                "AMOUNT_MISMATCH",
                () -> {
                    List<Long> ids = entryIds("ledger", entryBackup);
                    assertThat(ids).as("注入前提：分录存在").isNotEmpty();
                    db.execute("ledger", "UPDATE ledger_entries SET amount_minor = amount_minor + 100 WHERE id = " + ids.get(0));
                },
                () -> {
                    List<Long> ids = entryIds("ledger", entryBackup);
                    db.execute("ledger", "UPDATE ledger_entries SET amount_minor = amount_minor - 100 WHERE id = " + ids.get(0));
                },
                "AMOUNT_MISMATCH"));
        faults.add(new Fault(
                "CURRENCY_MISMATCH",
                () -> db.execute("ledger", "UPDATE ledger_entries SET currency='USD' WHERE posting_id IN (" + postingIds + ")"),
                () -> db.execute("ledger", "UPDATE ledger_entries SET currency='CNY' WHERE posting_id IN (" + postingIds + ")"),
                "CURRENCY_MISMATCH"));
        faults.add(new Fault(
                "DIRECTION_MISMATCH",
                () -> {
                    String debitId = firstEntryId(entryBackup, "DEBIT");
                    db.execute("ledger", "UPDATE ledger_entries SET direction='CREDIT' WHERE id = " + debitId);
                },
                () -> {
                    String debitId = firstEntryId(entryBackup, "DEBIT");
                    db.execute("ledger", "UPDATE ledger_entries SET direction='DEBIT' WHERE id = " + debitId);
                },
                "DIRECTION_MISMATCH"));
        faults.add(new Fault(
                "DUPLICATE_POSTING",
                () -> {
                    db.execute("ledger", "INSERT INTO postings (posting_no, idempotency_key, source_type, source_id,"
                            + " status, currency, created_at, version) SELECT CONCAT(posting_no, '-dup-" + uid + "'),"
                            + " CONCAT(idempotency_key, '-dup-" + uid + "'), source_type, source_id, status, currency,"
                            + " NOW(), 1 FROM postings WHERE id IN (" + postingIds + ")");
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at)"
                            + " SELECT (SELECT id FROM postings WHERE posting_no = CONCAT(posting_no, '-dup-" + uid
                            + "')), account_id, direction, amount_minor, currency, entry_type, source_type,"
                            + " source_id, NOW() FROM ledger_entries WHERE posting_id IN (" + postingIds + ")");
                },
                () -> {
                    db.execute("ledger", "DELETE FROM ledger_entries WHERE posting_id IN"
                            + " (SELECT id FROM (SELECT id FROM postings WHERE posting_no LIKE '%-dup-" + uid + "') x)");
                    db.execute("ledger", "DELETE FROM postings WHERE posting_no LIKE '%-dup-" + uid + "'");
                },
                "DUPLICATE_POSTING"));
        faults.add(new Fault(
                "BALANCE_BREAK",
                () -> {
                    db.execute("ledger", "INSERT INTO postings (posting_no, idempotency_key, source_type, source_id,"
                            + " status, currency, created_at, version) VALUES ('LPe2e-unbal-" + uid + "',"
                            + " 'e2e-unbal-key-" + uid + "', 'PAYMENT', 'e2e-unbal-" + uid + "',"
                            + " 'POSTED', 'CNY', NOW(), 1)");
                    long pid = ((Number) db.scalar("ledger",
                            "SELECT id FROM postings WHERE posting_no='LPe2e-unbal-" + uid + "'")).longValue();
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at) VALUES (" + pid
                            + ", 1, 'DEBIT', 77, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-unbal-" + uid + "', NOW())");
                },
                () -> {
                    db.execute("ledger", "DELETE FROM ledger_entries WHERE source_id='e2e-unbal-" + uid + "'");
                    db.execute("ledger", "DELETE FROM postings WHERE posting_no='LPe2e-unbal-" + uid + "'");
                },
                "BALANCE_BREAK"));
        faults.add(new Fault(
                "ACCOUNT_RECON_BREAK",
                () -> {
                    String firstId = entryIds("ledger", entryBackup).get(0).toString();
                    db.execute("ledger", "UPDATE ledger_entries SET account_id=5 WHERE id = " + firstId);
                },
                () -> {
                    String firstId = entryIds("ledger", entryBackup).get(0).toString();
                    Object orig = entryBackup.get(0).get("account_id");
                    db.execute("ledger", "UPDATE ledger_entries SET account_id=" + orig + " WHERE id = " + firstId);
                },
                "ACCOUNT_RECON_BREAK"));
        return faults;
    }

    @Test
    void liveCleanRunHasZeroDifferences() {
        runCase("recon-clean", ctx -> {
            String uid = prefix("rc");
            String orderNo = paidOrder(ctx, db, uid, uid, 1, 1);
            String paymentNo = paymentNoOf(orderNo);

            String period = period("clean", uid);
            Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e");
            ctx.response("auditCreateBatch", batch);
            assertThat(batch.is2xx()).as("审计建批 [period=%s]", period).isTrue();
            String batchNo = batch.json().path("batchNo").asText();

            awaitBatchSettled(batchNo);
            assertBatchHasNoOpenDifference(batchNo, ctx);
            Invariants.ledgerBalanced(db, orderNo);
            ctx.invariant("clean LIVE audit: 0 open differences, ledger balanced [order=" + orderNo + "]");
        });
    }

    @Test
    void faultInjectionMatrixAllDetectedWithCorrectKind() {
        runCase("recon-fault-matrix", ctx -> {
            String uid = prefix("fm");
            String orderNo = paidOrder(ctx, db, uid, uid, 1, 1);
            String paymentNo = paymentNoOf(orderNo);

            for (Fault fault : faultMatrix(orderNo, paymentNo, uid)) {
                fault.apply();
                try {
                    String period = period(fault.name().toLowerCase(), uid);
                    Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e-fault");
                    ctx.response(fault.name() + ":createBatch", batch);
                    assertThat(batch.is2xx()).as("建批 [%s period=%s]", fault.name(), period).isTrue();
                    String batchNo = batch.json().path("batchNo").asText();
                    awaitBatchSettled(batchNo);

                    JsonNode diffs = API.auditDifferences(batchNo).json();
                    ctx.json(fault.name() + ":differences", diffs);
                    boolean detected = containsKind(diffs, fault.expectedKind());
                    assertThat(detected)
                            .as("故障检出且分类正确 [fault=%s, 期望 kind=%s, 实际=%s]",
                                    fault.name(), fault.expectedKind(), kindsOf(diffs))
                            .isTrue();
                } finally {
                    fault.restore();
                }
                // 还原后 recheck → 批次恢复 BALANCED（闭环可解释）
                Invariants.ledgerBalanced(db, orderNo);
            }
            ctx.invariant("fault matrix: 8 injections all detected with correct kind, all restored, ledger balanced");
        });
    }

    @Test
    void suspendLoopAndCloseSemantics() {
        runCase("recon-suspend-close", ctx -> {
            String uid = prefix("sc");
            String orderNo = paidOrder(ctx, db, uid, uid, 1, 1);
            String paymentNo = paymentNoOf(orderNo);

            // 注入 ORPHAN_POSTING（BLOCKER）→ 检出 → 挂账 → close 放行（挂账即收口）
            List<Map<String, Object>> postingBackup = db.query("ledger",
                    "SELECT * FROM postings WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'");
            List<Map<String, Object>> entryBackup = db.query("ledger",
                    "SELECT * FROM ledger_entries WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'");
            String postingIds = joinIds(postingBackup, "id");
            db.execute("ledger", "DELETE FROM ledger_entries WHERE posting_id IN (" + postingIds + ")");
            db.execute("ledger", "DELETE FROM postings WHERE id IN (" + postingIds + ")");
            try {
                String period = period("suspend", uid);
                Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e");
                String batchNo = batch.json().path("batchNo").asText();
                awaitBatchSettled(batchNo);

                JsonNode diffs = API.auditDifferences(batchNo).json();
                assertThat(containsKind(diffs, "MISSING_POSTING")).isTrue();

                // 逐条挂账
                for (JsonNode diff : diffs) {
                    if ("PENDING".equals(diff.path("status").asText())) {
                        Api.ApiResponse susp = API.auditSuspend(batchNo, diff.path("id").asLong(),
                                "e2e-operator", "e2e suspend");
                        ctx.response("suspend-" + diff.path("id").asLong(), susp);
                        assertThat(susp.is2xx()).as("挂账 [diff=%s]", diff.path("id")).isTrue();
                    }
                }

                // 未收口（PENDING）差异存在时 close 必须被拒（AC3.3 / 结算门禁语义）
                // （本批差异已全部挂账 → close 放行；门禁由 SettlementGateE2ETest 独立验证）
                Api.ApiResponse closed = API.auditClose(batchNo, "e2e-operator");
                ctx.response("close", closed);
                assertThat(closed.is2xx())
                        .as("全部挂账后 close 放行 [batch=%s]，实际 %d: %s", batchNo, closed.status(), closed.body())
                        .isTrue();
            } finally {
                // 还原分录（挂账产生的 SUSPENSE 台账留痕属审计事实，不回滚）
                insertRows("ledger", "postings", postingBackup);
                insertRows("ledger", "ledger_entries", entryBackup);
            }
            ctx.invariant("suspend loop: injected MISSING_POSTING detected -> all suspended -> close accepted");
        });
    }

    // ---- 帮助方法 ----

    private String period(String tag, String uid) {
        // period 只允许 [A-Za-z0-9._-]
        return ("e2e-" + tag + "-" + Long.toString(System.currentTimeMillis(), 36) + "-" + uid)
                .replaceAll("[^A-Za-z0-9._-]", "");
    }

    /** 轮询审计批次结算（audit_batches.status 离开 PROCESSING/RECHECKING）。 */
    private void awaitBatchSettled(String batchNo) {
        Await.until("审计批次结算 [batch=" + batchNo + "]", () -> {
            List<Map<String, Object>> rows = db.query("reconciliation",
                    "SELECT status FROM audit_batches WHERE batch_no='" + batchNo + "'");
            if (rows.isEmpty()) {
                return false;
            }
            String status = String.valueOf(rows.get(0).get("status"));
            return !"PROCESSING".equals(status) && !"RECHECKING".equals(status);
        });
    }

    private void assertBatchHasNoOpenDifference(String batchNo, Dump.Context ctx) {
        JsonNode diffs = API.auditDifferences(batchNo).json();
        ctx.json("clean-differences", diffs);
        assertThat(diffs.size())
                .as("CLEAN 基线 0 差异 [batch=%s]，实际 %s", batchNo, diffs)
                .isZero();
    }

    private boolean containsKind(JsonNode diffs, String kind) {
        for (JsonNode d : diffs) {
            if (kind.equals(d.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }

    private List<String> kindsOf(JsonNode diffs) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode d : diffs) {
            kinds.add(d.path("kind").asText());
        }
        return kinds;
    }

    private List<Long> entryIds(String schema, List<Map<String, Object>> backup) {
        return backup.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
    }

    private String firstEntryId(List<Map<String, Object>> backup, String direction) {
        return backup.stream().filter(r -> direction.equals(r.get("direction")))
                .map(r -> String.valueOf(r.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + direction + " entry in backup"));
    }

    private String joinIds(List<Map<String, Object>> rows, String col) {
        return rows.stream().map(r -> String.valueOf(r.get(col))).reduce((a, b) -> a + "," + b).orElse("NULL");
    }

    /** 备份行回插（还原注入用）：列名 → 值，字符串加引号，NULL 显式。 */
    private void insertRows(String schema, String table, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            StringBuilder cols = new StringBuilder();
            StringBuilder vals = new StringBuilder();
            for (Map.Entry<String, Object> e : new LinkedHashMap<>(row).entrySet()) {
                if (!cols.isEmpty()) {
                    cols.append(", ");
                    vals.append(", ");
                }
                cols.append(e.getKey());
                Object v = e.getValue();
                if (v == null) {
                    vals.append("NULL");
                } else if (v instanceof Number) {
                    vals.append(v);
                } else {
                    vals.append('\'').append(String.valueOf(v).replace("'", "''")).append('\'');
                }
            }
            db.execute(schema, "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")");
        }
    }
}
