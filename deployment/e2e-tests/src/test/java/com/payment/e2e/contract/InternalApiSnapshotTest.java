package com.payment.e2e.contract;

import com.payment.e2e.support.Api;
import com.payment.e2e.support.Db;
import com.payment.e2e.support.Dump;
import com.payment.e2e.support.E2eBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 / FR-011（T428，D3）：内部 API schema 快照测试。
 *
 * <p>对关键接口响应做「字段集合 + 类型」递归快照，与
 * {@code src/test/resources/api-snapshots/*.json} 基线比对——字段增删 / 类型漂移即红，
 * 锁定内部契约（替代 Spring Cloud Contract / Pact，D3 决策）。</p>
 *
 * <p>契约变更时有意更新基线：{@code mvn test -De2e.env=local -De2e.update-snapshots=true}。</p>
 */
class InternalApiSnapshotTest extends E2eBase {

    private static final Path BASELINE_DIR =
            Path.of("src", "test", "resources", "api-snapshots");
    private static final boolean UPDATE =
            Boolean.getBoolean("e2e.update-snapshots");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final DbHolder dbHolder = new DbHolder();

    /** 惰性 DB（仅造单需要）。 */
    private static final class DbHolder {
        Db db;

        Db get() {
            if (db == null) {
                db = new Db();
            }
            return db;
        }
    }

    @Test
    void orderResponseSchemaIsStable() {
        snapshotEndpoint("order-detail", ctx -> {
            String uid = prefix("snap");
            String orderNo = paidOrder(ctx, dbHolder.get(), uid, uid, skuWithPrice(ctx, 2500L), 1);
            return API.getOrder(orderNo).json();
        });
    }

    @Test
    void paymentResponseSchemaIsStable() {
        snapshotEndpoint("payment-detail", ctx -> {
            String uid = prefix("snap");
            String orderNo = paidOrder(ctx, dbHolder.get(), uid, uid, skuWithPrice(ctx, 2500L), 1);
            return API.getPayment(paymentNoOf(dbHolder.get(), orderNo)).json();
        });
    }

    @Test
    void refundResponseSchemaIsStable() {
        snapshotEndpoint("refund-detail", ctx -> {
            String uid = prefix("snap");
            String orderNo = paidOrder(ctx, dbHolder.get(), uid, uid, skuWithPrice(ctx, 2500L), 1);
            var resp = API.refund(orderNo, null, 100L, "e2e snapshot");
            String pmrf = resp.json().path("pmrf").asText();
            return API.getRefund(pmrf).json();
        });
    }

    @Test
    void auditDifferenceSchemaIsStable() {
        snapshotEndpoint("audit-difference", ctx -> {
            // ORPHAN 注入造一条差异，取第一条 schema
            String uid = prefix("snap");
            dbHolder.get().execute("ledger", "INSERT INTO postings (posting_no, idempotency_key, source_type,"
                    + " source_id, status, currency, created_at, updated_at, version) VALUES ('LPe2e-snap-" + uid + "',"
                    + " 'e2e-snap-key-" + uid + "', 'PAYMENT', 'e2e-snap-" + uid + "',"
                    + " 'POSTED', 'CNY', NOW(), NOW(), 1)");
            long pid = ((Number) dbHolder.get().scalar("ledger",
                    "SELECT id FROM postings WHERE posting_no='LPe2e-snap-" + uid + "'")).longValue();
            dbHolder.get().execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction,"
                    + " amount_minor, currency, entry_type, source_type, source_id, created_at) VALUES (" + pid
                    + ", 3, 'DEBIT', 5, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-snap-" + uid + "', NOW())");
            dbHolder.get().execute("ledger", "INSERT INTO ledger_entries (posting_id, account_id, direction,"
                    + " amount_minor, currency, entry_type, source_type, source_id, created_at) VALUES (" + pid
                    + ", 3, 'CREDIT', 5, 'CNY', 'PAYMENT_CAPTURE', 'PAYMENT', 'e2e-snap-" + uid + "', NOW())");
            try {
                String period = "e2e-snap-" + Long.toString(System.currentTimeMillis(), 36);
                Api.ApiResponse batch = API.auditCreateBatch(period, "ALL", "e2e-snap");
                String batchNo = batch.json().path("batchNo").asText();
                JsonNode diffs = API.auditDifferences(batchNo).json();
                // 按注入 sourceId 精确取样：批内差异按 id 排序且含其他来源（历史数据/并发流量），
                // 取 get(0) 会把别条差异的 schema 当基线（reference 等可空字段类型随数据漂移）
                for (JsonNode d : diffs) {
                    if (("e2e-snap-" + uid).equals(d.path("sourceId").asText())) {
                        return d;
                    }
                }
                throw new IllegalStateException("注入的 ORPHAN 差异未检出 [batch=" + batchNo + "]");
            } finally {
                dbHolder.get().execute("ledger",
                        "DELETE FROM ledger_entries WHERE source_id='e2e-snap-" + uid + "'");
                dbHolder.get().execute("ledger",
                        "DELETE FROM postings WHERE posting_no='LPe2e-snap-" + uid + "'");
            }
        });
    }

    // ---- 快照机制 ----

    private void snapshotEndpoint(String name, java.util.function.Function<Dump.Context, JsonNode> producer) {
        runCase("snapshot-" + name, ctx -> {
            JsonNode response = producer.apply(ctx);
            ObjectNode schema = schemaOf(response);

            Path baseline = BASELINE_DIR.resolve(name + ".json");
            if (UPDATE || !Files.exists(baseline)) {
                writeBaseline(baseline, schema, name);
                return;
            }
            ObjectNode expected;
            try {
                expected = (ObjectNode) MAPPER.readTree(Files.readString(baseline, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("baseline unreadable: " + baseline, e);
            }
            assertThat(json(schema))
                    .as("API schema 漂移 [%s]：%n期望 %s%n实际 %s%n（契约有意变更时以"
                            + " -De2e.update-snapshots=true 重录基线）", name, json(expected), json(schema))
                    .isEqualTo(json(expected));
        });
    }

    /** writeValueAsString 的受检异常收敛包装。 */
    private String json(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    /** 递归抽取字段集合 + 类型（object → 子字段；array → 元素 schema；标量 → 类型名）。 */
    private ObjectNode schemaOf(JsonNode node) {
        ObjectNode schema = MAPPER.createObjectNode();
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                fields.put(e.getKey(), e.getValue());
            }
            ObjectNode props = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> e : fields.entrySet()) {
                props.set(e.getKey(), schemaOf(e.getValue()));
            }
            schema.put("type", "object");
            schema.set("fields", props);
        } else if (node.isArray()) {
            schema.put("type", "array");
            schema.set("items", node.size() > 0 ? schemaOf(node.get(0)) : MAPPER.createObjectNode());
        } else if (node.isTextual()) {
            schema.put("type", "string");
        } else if (node.isBoolean()) {
            schema.put("type", "boolean");
        } else if (node.isNumber()) {
            schema.put("type", node.isIntegralNumber() ? "integer" : "decimal");
        } else {
            schema.put("type", node.isNull() || node.isMissingNode() ? "null" : "unknown");
        }
        return schema;
    }

    private void writeBaseline(Path baseline, ObjectNode schema, String name) {
        try {
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, MAPPER.writeValueAsString(schema), StandardCharsets.UTF_8);
            System.out.println("[snapshot] baseline written: " + baseline + " (" + name + ")");
        } catch (IOException e) {
            throw new IllegalStateException("failed to write baseline " + baseline, e);
        }
    }
}
