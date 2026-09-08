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
                    // 备份必须非空：paidOrder 已等 posting 落定，空备份 → IN (NULL) 静默失效
                    assertThat(postingBackup)
                            .as("MISSING_POSTING 注入前置：posting 备份非空 [payment=%s]", paymentNo)
                            .isNotEmpty();
                    int delEntries = db.execute("ledger",
                            "DELETE FROM ledger_entries WHERE posting_id IN (" + postingIds + ")");
                    int delPostings = db.execute("ledger",
                            "DELETE FROM postings WHERE id IN (" + postingIds + ")");
                    assertThat(delPostings)
                            .as("MISSING_POSTING 注入自验证（posting 删除行数）[ids=%s]", postingIds)
                            .isGreaterThan(0);
                    assertThat(delEntries).as("分录删除行数 [ids=%s]", postingIds).isGreaterThan(0);
                    // 审计读路径收敛：建批前经与 recon 审计相同的两条读链路确认——
                    // ledger HTTP 读路径无该 posting（分录已删）且 payment confirmed-facts
                    // 含该支付（事实已在）——防池化连接旧快照类幽灵读（事实/分录双缺 → 伪 BALANCED）
                    awaitAuditReadPathSeesInjection(paymentNo);
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
                            + " status, currency, created_at, updated_at, version) VALUES ('LPe2e-orphan-" + uid + "',"
                            + " 'e2e-orphan-key-" + uid + "', 'PAYMENT', 'e2e-orphan-" + uid + "',"
                            + " 'POSTED', 'CNY', NOW(), NOW(), 1)");
                    long pid = ((Number) db.scalar("ledger",
                            "SELECT id FROM postings WHERE posting_no='LPe2e-orphan-" + uid + "'")).longValue();
                    // 平衡双分录挂 3 号科目（CUSTOMER_CASH=1/MERCHANT_PAYABLE=2 参与勾稽，单分录会触发
                    // allPostings 的 domain 校验 400—— posting 级平衡在读路径强制）
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at) VALUES (" + pid
                            + ", 3, 'DEBIT', 100, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-orphan-" + uid + "', NOW())");
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at) VALUES (" + pid
                            + ", 2, 'CREDIT', 100, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-orphan-" + uid + "', NOW())");
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
                    // 两步式：先复制 posting 行拿到新 id，再显式回填复制分录
                    // （子查询内层 posting_no 会解析到自身，永远匹配不上，不能一步 SELECT 回填）
                    db.execute("ledger", "INSERT INTO postings (posting_no, idempotency_key, source_type, source_id,"
                            + " status, currency, created_at, updated_at, version) SELECT CONCAT(posting_no, '-dup-" + uid + "'),"
                            + " CONCAT(idempotency_key, '-dup-" + uid + "'), source_type, source_id, status, currency,"
                            + " NOW(), NOW(), 1 FROM postings WHERE id IN (" + postingIds + ")");
                    long dupPid = ((Number) db.scalar("ledger",
                            "SELECT id FROM postings WHERE posting_no LIKE '%-dup-" + uid + "' LIMIT 1")).longValue();
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at)"
                            + " SELECT " + dupPid + ", account_id, direction, amount_minor, currency, entry_type,"
                            + " source_type, source_id, NOW() FROM ledger_entries WHERE posting_id IN (" + postingIds + ")");
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
                    // posting 级平衡在读路径强制（Posting.rehydrate），不可读的失衡分录会让 facts read 400。
                    // 全局失衡用「游离分录」（挂不存在的 posting_id）：allPostings 可读，balance() 按币种差额非 0
                    db.execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction, amount_minor,"
                            + " currency, entry_type, source_type, source_id, created_at) VALUES (999999999,"
                            + " 1, 'DEBIT', 77, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-unbal-" + uid + "', NOW())");
                },
                () -> {
                    db.execute("ledger", "DELETE FROM ledger_entries WHERE source_id='e2e-unbal-" + uid + "'");
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
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);

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
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);

            for (Fault fault : faultMatrix(orderNo, paymentNo, uid)) {
                // 每次尝试 apply/restore 严格配对；未检出（池化连接旧快照类幽灵读）
                // 则还原后重注入重试，最多 3 次
                JsonNode diffs = null;
                boolean detected = false;
                int attempt = 0;
                while (attempt < 3 && !detected) {
                    attempt++;
                    fault.apply();
                    try {
                        String period = period(fault.name().toLowerCase() + "-a" + attempt, uid);
                        Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e-fault");
                        ctx.response(fault.name() + ":createBatch-a" + attempt, batch);
                        assertThat(batch.is2xx()).as("建批 [%s 第%d次 period=%s]", fault.name(), attempt, period).isTrue();
                        String batchNo = batch.json().path("batchNo").asText();
                        awaitBatchSettled(batchNo);

                        diffs = API.auditDifferences(batchNo).json();
                        ctx.json(fault.name() + ":differences-a" + attempt, diffs);
                        detected = containsKind(diffs, fault.expectedKind());
                    } finally {
                        fault.restore();
                    }
                }
                assertThat(detected)
                        .as("故障检出且分类正确 [fault=%s, 期望 kind=%s, 尝试=%d 次, 实际=%s]",
                                fault.name(), fault.expectedKind(), attempt, kindsOf(diffs))
                        .isTrue();
                // 还原后账本恢复平衡（闭环可解释）
                Invariants.ledgerBalanced(db, orderNo);
            }
            ctx.invariant("fault matrix: 8 injections all detected with correct kind, all restored, ledger balanced");
        });
    }

    @Test
    void suspendLoopAndCloseSemantics() {
        runCase("recon-suspend-close", ctx -> {
            String uid = prefix("sc");
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);

            // 注入 ORPHAN_POSTING（BLOCKER）→ 检出 → 挂账 → close 放行（挂账即收口）
            List<Map<String, Object>> postingBackup = db.query("ledger",
                    "SELECT * FROM postings WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'");
            List<Map<String, Object>> entryBackup = db.query("ledger",
                    "SELECT * FROM ledger_entries WHERE source_type='PAYMENT' AND source_id='" + paymentNo + "'");
            String postingIds = joinIds(postingBackup, "id");
            db.execute("ledger", "DELETE FROM ledger_entries WHERE posting_id IN (" + postingIds + ")");
            db.execute("ledger", "DELETE FROM postings WHERE id IN (" + postingIds + ")");
            // 审计读路径收敛（同 fault matrix）
            awaitAuditReadPathSeesInjection(paymentNo);
            {
                String period = period("suspend", uid);
                Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e");
                String batchNo = batch.json().path("batchNo").asText();
                awaitBatchSettled(batchNo);

                JsonNode diffs = API.auditDifferences(batchNo).json();
                assertThat(containsKind(diffs, "MISSING_POSTING")).isTrue();

                // 闭环处置链（FR-014~FR-018）：挂账（资金缺口安置到 SUSPENSE 过渡科目）
                // → TRANSFER 转出到 MERCHANT_PAYABLE（查清归属）
                // → recheck（勾稽差异随账实一致自动收口）→ close 放行。
                // 注：删除 posting 必然连带 MERCHANT_PAYABLE 勾稽差异（A2 口径），
                // 仅挂账不转出时该差异无法收口（挂账不触碰应付商户科目）。
                for (JsonNode diff : diffs) {
                    if ("PENDING".equals(diff.path("status").asText())
                            && "MISSING_POSTING".equals(diff.path("kind").asText())) {
                        long diffId = diff.path("id").asLong();
                        long amount = diff.path("expectedAmountMinor").asLong()
                                - diff.path("actualAmountMinor").asLong();
                        Api.ApiResponse susp = API.auditSuspend(batchNo, diffId,
                                "e2e-operator", "e2e suspend");
                        ctx.response("suspend-" + diffId, susp);
                        assertThat(susp.is2xx()).as("挂账 [diff=%s]", diffId).isTrue();
                        Api.ApiResponse transfer = API.auditAdjust(batchNo, diffId, "TRANSFER", amount,
                                "MERCHANT_PAYABLE", "e2e-operator", "e2e-reviewer", "e2e transfer to payable");
                        ctx.response("transfer-" + diffId, transfer);
                        assertThat(transfer.is2xx())
                                .as("SUSPENSE 转出到 MERCHANT_PAYABLE [diff=%s]，实际 %d: %s",
                                        diffId, transfer.status(), transfer.body())
                                .isTrue();
                    }
                }

                // 显式 recheck（FR-017）：全批重算——勾稽差异在账实一致后置 VERIFIED。
                // 重试至收口：TRANSFER 分录写入后若 recompute 撞上池化连接旧快照会短暂
                // 读不到（幽灵读），重跑 recheck 即可收敛
                Await.until("全批收口 [batch=" + batchNo + "]", () -> {
                    Api.ApiResponse rechecked = API.auditRecheck(batchNo);
                    ctx.response("recheck", rechecked);
                    if (!rechecked.is2xx()) {
                        return false;
                    }
                    JsonNode after = API.auditDifferences(batchNo).json();
                    for (JsonNode d : after) {
                        if (!"VERIFIED".equals(d.path("status").asText())) {
                            return false;
                        }
                    }
                    return true;
                });

                Api.ApiResponse closed = API.auditClose(batchNo, "e2e-operator");
                ctx.response("close", closed);
                assertThat(closed.is2xx())
                        .as("挂账+转出+recheck 后 close 放行 [batch=%s]，实际 %d: %s",
                                batchNo, closed.status(), closed.body())
                        .isTrue();
            }
            // 不还原分录：挂账（DR 客户资金/CR SUSPENSE）+ 转出（DR SUSPENSE/CR 应付商户）
            // 的净效应恰好复刻被删 posting 的借贷影响，账实已一致；若再还原会双重贷记
            // MERCHANT_PAYABLE（+2500），CLEAN 基线将出现勾稽差异
            ctx.invariant(
                    "suspend loop: injected MISSING_POSTING detected -> suspended -> transferred -> recheck -> close accepted");
        });
    }

    // ---- 帮助方法 ----

    private String period(String tag, String uid) {
        // period 只允许 [A-Za-z0-9._-]，且受 audit_batches.period VARCHAR(32) 约束：
        // 超长时保留可读前缀 + 内容 hash 尾巴，保证唯一性
        String raw = ("e2e-" + tag + "-" + Long.toString(System.currentTimeMillis(), 36) + "-" + uid)
                .replaceAll("[^A-Za-z0-9._-]", "");
        if (raw.length() <= 32) {
            return raw;
        }
        String hashTail = Long.toString(raw.hashCode() & 0xffffffffL, 36);
        return raw.substring(0, 32 - hashTail.length() - 1) + "-" + hashTail;
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

    /** 审计读路径收敛：ledger 读路径无该 posting 且 payment confirmed-facts 含该支付。 */
    private void awaitAuditReadPathSeesInjection(String paymentNo) {
        Await.until("审计读路径收敛 [payment=" + paymentNo + "]", () ->
                !ledgerPostingsContain(paymentNo) && factsFeedContains(paymentNo));
    }

    /** ledger HTTP 读路径（allPostings）是否仍含该支付的分录。 */
    private boolean ledgerPostingsContain(String paymentNo) {
        Api.ApiResponse all = API.get("ledger", "/internal/ledger/postings/all");
        for (JsonNode posting : all.json()) {
            if (paymentNo.equals(posting.path("sourceId").asText())) {
                return true;
            }
        }
        return false;
    }

    /** payment confirmed-facts 事实流是否已含该支付（账证核对的事实源）。 */
    private boolean factsFeedContains(String paymentNo) {
        Api.ApiResponse facts = API.get("payment", "/internal/payments/confirmed-facts");
        for (JsonNode fact : facts.json()) {
            if (paymentNo.equals(fact.path("paymentNo").asText())) {
                return true;
            }
        }
        return false;
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
