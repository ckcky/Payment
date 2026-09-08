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
import java.util.UUID;

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

    /**
     * 单个故障注入定义：apply 施加、restore 还原、expectedKind 期望检出的差异类型。
     * expectedSourceId 为该注入的差异身份（差异 sourceId），检出断言按 kind+身份匹配——
     * 共享演示环境存在并发流量（在途支付的瞬时 MISSING 等），仅按 kind 匹配会把
     * ambient 噪声当注入成功、也会淹没真实注入（v9k/v9l 实证）。账户级差异无业务
     * sourceId（sourceId 为科目名），传 null 退化为仅按 kind 匹配。
     */
    private record Fault(String name, Runnable apply, Runnable restore,
                         String expectedKind, String expectedSourceId) {
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
                    // 备份必须非空：paidOrder 已等 posting 落定，空备份 → 改写无从谈起
                    assertThat(postingBackup)
                            .as("MISSING_POSTING 注入前置：posting 备份非空 [payment=%s]", paymentNo)
                            .isNotEmpty();
                    // 用 UPDATE 改写 source_id 制造「事实有、分录无」——而非 DELETE。
                    // 审计器按 (sourceType, sourceId) 匹配 → fact 无匹配 posting → MISSING 检出；
                    // 科目金额不变 → 勾稽不受影响。改写行会额外产生 ORPHAN（voided-* 无事实）。
                    //
                    // 落库回读验证：写通道返回 affected>0 不代表真落库——本机沙箱存在间歇性
                    // 「请求被吞+伪造成功响应」（v9q/v9t 实证：mock-channel-web 访问日志与 MySQL
                    // general_log 均无该语句，测试却拿到 affected>0）。故注入后必须经 JDBC 读
                    // 通道（读路径全程可靠）回读核验，未落库则重试，超时则以「沙箱吞写」显式失败。
                    updateLanded("ledger",
                            "UPDATE postings SET source_id = CONCAT('voided-', source_id) WHERE id IN ("
                                    + postingIds + ") AND source_id NOT LIKE 'voided-%'",
                            "SELECT id, source_id FROM postings WHERE id IN (" + postingIds + ")",
                            rows -> !rows.isEmpty() && rows.stream()
                                    .allMatch(r -> String.valueOf(r.get("source_id")).startsWith("voided-")),
                            "MISSING_POSTING 注入落库（回读核验）[payment=" + paymentNo + "]");
                    // 审计读路径收敛：确认改写已对审计读链路生效且事实流含该支付
                    awaitAuditReadPathSeesInjection(paymentNo);
                },
                () -> {
                    for (Map<String, Object> row : postingBackup) {
                        String origId = String.valueOf(row.get("id"));
                        String origSrc = String.valueOf(row.get("source_id"));
                        updateLanded("ledger",
                                "UPDATE postings SET source_id='" + origSrc + "' WHERE id = " + origId,
                                "SELECT id, source_id FROM postings WHERE id = " + origId,
                                rows -> rows.size() == 1 && origSrc.equals(String.valueOf(rows.get(0).get("source_id"))),
                                "MISSING_POSTING 还原落库（回读核验）[id=" + origId + "]");
                    }
                },
                "MISSING_POSTING", paymentNo));
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
                "ORPHAN_POSTING", "e2e-orphan-" + uid));
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
                "AMOUNT_MISMATCH", paymentNo));
        faults.add(new Fault(
                "CURRENCY_MISMATCH",
                () -> db.execute("ledger", "UPDATE ledger_entries SET currency='USD' WHERE posting_id IN (" + postingIds + ")"),
                () -> db.execute("ledger", "UPDATE ledger_entries SET currency='CNY' WHERE posting_id IN (" + postingIds + ")"),
                "CURRENCY_MISMATCH", paymentNo));
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
                "DIRECTION_MISMATCH", paymentNo));
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
                "DUPLICATE_POSTING", paymentNo));
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
                "BALANCE_BREAK", "e2e-unbal-" + uid));
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
                "ACCOUNT_RECON_BREAK", null));
        return faults;
    }

    @Test
    void liveCleanRunHasZeroDifferences() {
        runCase("recon-clean", ctx -> {
            String uid = prefix("rc");
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);

            // ambient 防抖：共享演示环境的并发流量存在「支付已确认、分录在途」的瞬时窗口，
            // 撞上即产生与本用例无关的瞬时 MISSING_POSTING；重试建批最多 3 次让窗口翻篇。
            // 注意 attempt 后缀必须进 period()（其内部处理 32 字符截断），拼在外部会超列宽
            // （v9m 实证：Data too long for column 'period' → 建批 500）
            String batchNo = null;
            JsonNode diffs = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                String period = period("clean" + (attempt > 1 ? "-r" + attempt : ""), uid);
                Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e");
                ctx.response("auditCreateBatch-a" + attempt, batch);
                assertThat(batch.is2xx()).as("审计建批 [period=%s 第%d次]", period, attempt).isTrue();
                batchNo = batch.json().path("batchNo").asText();
                awaitBatchSettled(batchNo);
                diffs = API.auditDifferences(batchNo).json();
                ctx.json("clean-differences-a" + attempt, diffs);
                if (diffs.isEmpty()) {
                    break;
                }
            }
            assertThat(diffs.size())
                    .as("CLEAN 基线 0 差异 [batch=%s]，实际 %s", batchNo, diffs)
                    .isZero();
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
                        detected = containsKindAndSource(diffs, fault.expectedKind(), fault.expectedSourceId());
                    } finally {
                        fault.restore();
                    }
                }
                assertThat(detected)
                        .as("故障检出且分类正确 [fault=%s, 期望 kind=%s, 身份=%s, 尝试=%d 次, 实际=%s]",
                                fault.name(), fault.expectedKind(), fault.expectedSourceId(), attempt, kindsOf(diffs))
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
            List<String> adjustNos = new ArrayList<>();   // 收集挂账/转出的 ADJUSTMENT 单号（还原用）
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
                // 按注入身份匹配（同 fault matrix）：ambient 流量的瞬时 MISSING 不得劫持本用例
                assertThat(containsKindAndSource(diffs, "MISSING_POSTING", paymentNo))
                        .as("注入的 MISSING 差异检出 [payment=%s]，实际 %s", paymentNo, kindsOf(diffs))
                        .isTrue();

                // 闭环处置链（FR-014~FR-018）：挂账（资金缺口安置到 SUSPENSE 过渡科目）
                // → TRANSFER 转出到 MERCHANT_PAYABLE（查清归属）
                // → recheck（勾稽差异随账实一致自动收口）→ close 放行。
                // 注：删除 posting 必然连带 MERCHANT_PAYABLE 勾稽差异（A2 口径），
                // 仅挂账不转出时该差异无法收口（挂账不触碰应付商户科目）。
                List<String> adjustNosLocal = new ArrayList<>();
                for (JsonNode diff : diffs) {
                    if ("PENDING".equals(diff.path("status").asText())
                            && "MISSING_POSTING".equals(diff.path("kind").asText())
                            && paymentNo.equals(diff.path("sourceId").asText())) {
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
                        adjustNos.add(transfer.json().path("adjustNo").asText());
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
            // 彻底还原：删除挂账+转出生成的 ADJUSTMENT 分录并回插原 posting。
            // 挂账/转出只是记账替代（勾稽平衡），不消除「fact-vs-posting」的账证差异——
            // 若不还原，之后每个新批次都会对该支付报 MISSING，永久污染 CLEAN 基线
            // 与后续运行（v9k→v9m 实证：PM222891546675204096 残留跨运行存在）
            if (!adjustNos.isEmpty()) {
                String in = adjustNos.stream().map(s -> "'" + s + "'")
                        .reduce((a, b) -> a + "," + b).orElse("''");
                db.execute("ledger", "DELETE FROM ledger_entries WHERE posting_id IN"
                        + " (SELECT id FROM postings WHERE source_type='ADJUSTMENT' AND source_id IN (" + in + "))");
                db.execute("ledger", "DELETE FROM postings WHERE source_type='ADJUSTMENT' AND source_id IN (" + in + ")");
                insertRows("ledger", "postings", postingBackup);
                insertRows("ledger", "ledger_entries", entryBackup);
            }
            ctx.invariant(
                    "suspend loop: injected MISSING_POSTING detected -> suspended -> transferred -> recheck -> close accepted, ledger fully restored");
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

    /**
     * 按 kind + 注入身份（sourceId）匹配。共享演示环境的并发流量存在
     * 「支付已确认、分录在途」的瞬时 MISSING（traffic-gen 实测），仅按 kind
     * 匹配会把 ambient 噪声当注入成功、也会淹没真实注入；sourceId 为 null
     * （账户级差异）时退化为仅按 kind。
     */
    private boolean containsKindAndSource(JsonNode diffs, String kind, String sourceId) {
        for (JsonNode d : diffs) {
            if (!kind.equals(d.path("kind").asText())) {
                continue;
            }
            if (sourceId == null || sourceId.equals(d.path("sourceId").asText())) {
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

    /**
     * 审计读路径收敛：建批前经与 recon 审计相同的两条读链路确认——ledger HTTP 读无该
     * posting 且 payment confirmed-facts 含该支付。
     *
     * <p>cache-busting：两读均带唯一 query 参数。沙箱透明代理会缓存同名 GET
     * （v9l/v9m 实证：轮询未达 ledger 服务即通过——读到旧快照使「分录已删」
     * 空洞成立），唯一 URL 强制回源。非 2xx 视为未收敛，不得空洞通过。</p>
     */
    /**
     * 带落库回读验证的写操作：执行 sql 后经 JDBC 读通道（读路径可靠）核验效果，
     * 未达标则重试直至超时（超时抛 ConditionTimeoutException，别名即失败原因）。
     * 本机沙箱存在「写请求被吞+伪造成功响应」（v9q/v9t 实证），写后必须回读，
     * 绝不信任 affected>0。
     */
    private void updateLanded(String schema, String sql, String readBackSql,
                              java.util.function.Predicate<List<Map<String, Object>>> ok, String alias) {
        Await.until(alias, () -> {
            db.execute(schema, sql);
            return ok.test(db.query(schema, readBackSql));
        });
    }

    private void awaitAuditReadPathSeesInjection(String paymentNo) {
        Await.until("审计读路径收敛 [payment=" + paymentNo + "]", () ->
                !ledgerPostingsContain(paymentNo) && factsFeedContains(paymentNo));
    }

    /** ledger HTTP 读路径（allPostings）是否仍含该支付的分录（URL 唯一强制回源）。 */
    private boolean ledgerPostingsContain(String paymentNo) {
        Api.ApiResponse all = API.get("ledger",
                "/internal/ledger/postings/all?_cb=" + UUID.randomUUID());
        if (!all.is2xx()) {
            return true;    // 读失败视为「未收敛」，继续轮询
        }
        for (JsonNode posting : all.json()) {
            if (paymentNo.equals(posting.path("sourceId").asText())) {
                return true;
            }
        }
        return false;
    }

    /** payment confirmed-facts 事实流是否已含该支付（URL 唯一强制回源）。 */
    private boolean factsFeedContains(String paymentNo) {
        Api.ApiResponse facts = API.get("payment",
                "/internal/payments/confirmed-facts?_cb=" + UUID.randomUUID());
        if (!facts.is2xx()) {
            return false;   // 读失败视为「未收敛」，继续轮询
        }
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
