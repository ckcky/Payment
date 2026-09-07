package com.payment.e2e.recon;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Await;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 / AC3.4（T421）：渠道对账差异注入——E2E 运行时向
 * {@code reconciliation.statement-dir-override}（默认 {@code /tmp/e2e-channel-statements}，
 * 部署侧已在 reconciliation-service application.yml 配置）落盘 {@code {period}.csv}，
 * 确定性注入 4 类账实差异，验证 LEDGER_VS_STATEMENT_BREAK 检出率 100% 且分类正确：
 *
 * <ul>
 *   <li><b>长款</b>：账单含幻影渠道流水（账本无）→ 检出；</li>
 *   <li><b>短款/单边账</b>：账单缺行（账本有真实流水）→ 检出；</li>
 *   <li><b>金额不符</b>：同 reference 金额错配 → 检出；</li>
 *   <li><b>重复流水</b>：同 reference 两行 → 检出。</li>
 * </ul>
 *
 * <p>覆盖目录不可写（未配置 / 权限缺失）时按 Assumptions 跳过——不产生假红（NFR-005）。</p>
 */
class ChannelStatementDiffE2ETest extends E2eBase {

    /** 与 reconciliation-service application.yml 的 statement-dir-override 保持一致。 */
    private static final Path OVERRIDE_DIR = Path.of(
            System.getProperty("e2e.statement-dir-override", "/tmp/e2e-channel-statements"));

    private final Db db = new Db();

    @Test
    void phantomStatementRowIsDetectedAsLong() {
        runCase("csv-diff-long", ctx -> {
            requireOverrideDir();
            String uid = prefix("csvl");
            paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);

            String period = "e2e-long-" + Long.toString(System.currentTimeMillis(), 36);
            writeStatement(period, "reference,amountMinor,currencyCode,status\n"
                    + "e2e-phantom-" + uid + ",999,CNY,SUCCEEDED\n");

            JsonNode diffs = runAudit(ctx, period);
            assertThat(hasKind(diffs, "LEDGER_VS_STATEMENT_BREAK"))
                    .as("幻影渠道流水必须检出为账实差异（长款）[period=%s]", period).isTrue();
        });
    }

    @Test
    void missingStatementRowsAreDetectedAsShort() {
        runCase("csv-diff-short", ctx -> {
            requireOverrideDir();
            String uid = prefix("csvs");
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);

            String period = "e2e-short-" + Long.toString(System.currentTimeMillis(), 36);
            writeStatement(period, "reference,amountMinor,currencyCode,status\n"); // 仅表头

            JsonNode diffs = runAudit(ctx, period);
            assertThat(hasKind(diffs, "LEDGER_VS_STATEMENT_BREAK"))
                    .as("账单缺行必须检出为账实差异（短款/单边账）[period=%s, payment=%s]", period, paymentNo)
                    .isTrue();
        });
    }

    @Test
    void amountMismatchedStatementRowIsDetected() {
        runCase("csv-diff-amount", ctx -> {
            requireOverrideDir();
            String uid = prefix("csva");
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);
            String channelRef = channelReferenceOf(paymentNo);

            // 同 reference、金额减 1 分 → 金额不符
            String period = "e2e-amt-" + Long.toString(System.currentTimeMillis(), 36);
            writeStatement(period, "reference,amountMinor,currencyCode,status\n"
                    + channelRef + ",2499,CNY,SUCCEEDED\n");

            JsonNode diffs = runAudit(ctx, period);
            assertThat(hasKind(diffs, "LEDGER_VS_STATEMENT_BREAK"))
                    .as("同渠道流水金额错配必须检出 [period=%s, ref=%s]", period, channelRef).isTrue();
        });
    }

    @Test
    void duplicatedStatementRowsAreDetected() {
        runCase("csv-diff-duplicate", ctx -> {
            requireOverrideDir();
            String uid = prefix("csvd");
            String orderNo = paidOrder(ctx, db, uid, uid, skuWithPrice(ctx, 2500L), 1);
            String paymentNo = paymentNoOf(db, orderNo);
            String channelRef = channelReferenceOf(paymentNo);

            // 同 reference 两行（重复投递形态）
            String period = "e2e-dup-" + Long.toString(System.currentTimeMillis(), 36);
            writeStatement(period, "reference,amountMinor,currencyCode,status\n"
                    + channelRef + ",2500,CNY,SUCCEEDED\n"
                    + channelRef + ",2500,CNY,SUCCEEDED\n");

            JsonNode diffs = runAudit(ctx, period);
            assertThat(hasKind(diffs, "LEDGER_VS_STATEMENT_BREAK"))
                    .as("重复渠道流水必须检出 [period=%s, ref=%s]", period, channelRef).isTrue();
        });
    }

    // ---- 帮助方法 ----

    private void requireOverrideDir() {
        boolean ready;
        try {
            Files.createDirectories(OVERRIDE_DIR);
            ready = Files.isWritable(OVERRIDE_DIR);
        } catch (IOException e) {
            ready = false;
        }
        Assumptions.assumeTrue(ready,
                "statement-dir-override 不可写（未配置 / 权限缺失），跳过 CSV 差异注入用例: " + OVERRIDE_DIR);
    }

    private void writeStatement(String period, String content) {
        try {
            Files.writeString(OVERRIDE_DIR.resolve(period + ".csv"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write statement csv for period " + period, e);
        }
    }

    private JsonNode runAudit(Dump.Context ctx, String period) {
        Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e-csv");
        ctx.response("auditCreateBatch", batch);
        assertThat(batch.is2xx()).as("审计建批 [period=%s]", period).isTrue();
        String batchNo = batch.json().path("batchNo").asText();
        Await.until("审计批次结算 [batch=" + batchNo + "]", () -> {
            List<Map<String, Object>> rows = db.query("reconciliation",
                    "SELECT status FROM audit_batches WHERE batch_no='" + batchNo + "'");
            if (rows.isEmpty()) {
                return false;
            }
            String status = String.valueOf(rows.get(0).get("status"));
            return !"PROCESSING".equals(status) && !"RECHECKING".equals(status);
        });
        JsonNode diffs = API.auditDifferences(batchNo).json();
        ctx.json("differences", diffs);
        return diffs;
    }

    private String channelReferenceOf(String paymentNo) {
        List<Map<String, Object>> rows = db.query("payment",
                "SELECT channel_reference FROM payment_attempts WHERE payment_no='" + paymentNo
                        + "' AND channel_reference IS NOT NULL ORDER BY id DESC LIMIT 1");
        assertThat(rows).as("渠道引用存在 [payment=%s, 表=payment.payment_attempts]", paymentNo).isNotEmpty();
        return String.valueOf(rows.get(0).get("channel_reference"));
    }

    /** 真实支付金额（分）——账单行金额必须与之对齐，不假设 SKU 价格。 */
    private long paidAmountMinorOf(String paymentNo) {
        List<Map<String, Object>> rows = db.query("payment",
                "SELECT amount_minor FROM payments WHERE payment_no='" + paymentNo + "'");
        assertThat(rows).as("支付单存在 [payment=%s, 表=payment.payments]", paymentNo).isNotEmpty();
        return ((Number) rows.get(0).get("amount_minor")).longValue();
    }

    private boolean hasKind(JsonNode diffs, String kind) {
        for (JsonNode d : diffs) {
            if (kind.equals(d.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }
}
