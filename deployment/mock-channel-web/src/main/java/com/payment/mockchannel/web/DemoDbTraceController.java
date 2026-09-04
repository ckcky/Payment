package com.payment.mockchannel.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 全链路 DB 诊断（演示控制台 ③）：按订单只读直查各系统落库记录。
 *
 * <p>定位（ADR-0048 修订版）：演示组件的只读诊断面 —— 只 SELECT，不写任何表；
 * 各库共实例（database-per-service，见 deployment/schema/*.sql），查询用「库名.表名」限定。
 * 每个 section 独立 try/catch，局部失败不拖垮整份报告。</p>
 *
 * <p>关联口径（ADR-0063：跨系统关联一律用业务单号，数值主键不跨服务）：</p>
 * <ul>
 *   <li>orders.order_no（OR+雪花）是对外业务单号；orders.id 仅为本服务主键。</li>
 *   <li>order_items / transactions / payments / refunds / fulfillments / entitlements
 *       的 order_no 均存业务单号。</li>
 *   <li>payment_attempts.payment_no 存业务单号（不再落数值 payment_id）。</li>
 *   <li>settlement_items.reference ← 对账匹配事实的渠道引用（attempt.channel_reference），
 *       反查 settlement_batches 经 items.batch_id。</li>
 *   <li>ledger postings.source_id —— PAYMENT→payments.id / REFUND→refunds.id /
 *       SETTLEMENT→settlement_batches.id（账本内部口径，未跨服务暴露）。</li>
 *   <li>reconciliation_batches 的匹配/差异内嵌在 matches_json/differences_json，
 *       按渠道引用文本 LIKE 反查。</li>
 * </ul>
 */
@RestController
public class DemoDbTraceController {

    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    private final JdbcTemplate jdbc;

    public DemoDbTraceController(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbc = jdbcProvider.getIfAvailable();
    }

    /** 兼容入参：传业务单号 orderNo（OR+雪花）或历史数值 orderId 均可识别。 */
    @GetMapping("/demo/trace")
    public Map<String, Object> trace(@RequestParam("orderId") String orderId,
                                     HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orderId", orderId);

        List<Map<String, Object>> sections = new ArrayList<>();
        resp.put("sections", sections);

        if (jdbc == null) {
            sections.add(errorSection("—", "—", "JdbcTemplate 不可用（依赖缺失）"));
            return resp;
        }

        // ① 订单主表：数值按 id 查，否则按 order_no 查
        boolean numeric = NUMERIC.matcher(orderId).matches();
        List<Map<String, Object>> orders = query(sections, "order-service", "orders",
                numeric ? "SELECT * FROM `order`.orders WHERE id = ?"
                        : "SELECT * FROM `order`.orders WHERE order_no = ?",
                new Object[]{numeric ? Long.parseLong(orderId) : orderId});
        if (orders.isEmpty()) {
            resp.put("found", false);
            resp.put("note", "orders 表无此记录（orderId/orderNo=" + orderId + "），后续系统无从关联");
            return resp;
        }
        resp.put("found", true);
        Map<String, Object> order = orders.get(0);
        String orderNo = String.valueOf(order.get("order_no"));
        Long orderIdNum = order.get("id") instanceof Number n ? n.longValue() : null;

        // ② 订单明细 + 交易单（order-service 库，按 order_no 关联）
        query(sections, "order-service", "order_items",
                "SELECT * FROM `order`.order_items WHERE order_no = ?", new Object[]{orderNo});
        query(sections, "order-service", "transactions",
                "SELECT * FROM `order`.transactions WHERE order_no = ?", new Object[]{orderNo});

        // ③ 支付单（order_no 关联）+ 尝试记录（payment_no 关联），收集渠道引用供结算/对账反查
        List<Map<String, Object>> payments = query(sections, "payment-service", "payments",
                "SELECT * FROM payment.payments WHERE order_no = ?", new Object[]{orderNo});
        List<Object> paymentNos = ids(payments, "payment_no");
        List<Map<String, Object>> attempts = query(sections, "payment-service", "payment_attempts",
                "SELECT * FROM payment.payment_attempts WHERE payment_no IN (" + placeholders(paymentNos) + ")",
                paymentNos.toArray());
        List<Object> channelRefs = new ArrayList<>();
        for (Map<String, Object> a : attempts) {
            Object ref = a.get("channel_reference");
            if (ref != null && !String.valueOf(ref).isBlank()) {
                channelRefs.add(ref);
            }
        }

        // ④ 退款 / 履约 / 权益（各自库，order_no 关联）
        List<Map<String, Object>> refunds = query(sections, "refund-service", "refunds",
                "SELECT * FROM refund.refunds WHERE order_no = ?", new Object[]{orderNo});
        List<Object> refundIds = ids(refunds, "id");
        query(sections, "fulfillment-service", "fulfillments",
                "SELECT * FROM fulfillment.fulfillments WHERE order_no = ?", new Object[]{orderNo});
        query(sections, "entitlement-service", "entitlements",
                "SELECT * FROM entitlement.entitlements WHERE order_no = ?", new Object[]{orderNo});

        // ⑤ 结算：items.reference 是渠道引用（对账匹配事实），反查所属批次
        List<Map<String, Object>> settleItems = query(sections, "settlement-service", "settlement_items",
                "SELECT * FROM settlement.settlement_items WHERE reference IN (" + placeholders(channelRefs) + ")",
                channelRefs.toArray());
        List<Object> batchIds = ids(settleItems, "batch_id");
        query(sections, "settlement-service", "settlement_batches",
                "SELECT * FROM settlement.settlement_batches WHERE id IN (" + placeholders(batchIds) + ")",
                batchIds.toArray());

        // ⑥ 对账批次：匹配/差异以 JSON 内嵌，按渠道引用文本反查
        if (channelRefs.isEmpty()) {
            sections.add(emptySection("reconciliation-service", "reconciliation_batches",
                    "（该订单尚无渠道引用 —— 未过渠道，或对账批次未建）"));
        } else {
            StringBuilder sql = new StringBuilder("SELECT * FROM reconciliation.reconciliation_batches WHERE (");
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < channelRefs.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("matches_json LIKE ? OR differences_json LIKE ?");
                args.add("%" + channelRefs.get(i) + "%");
                args.add("%" + channelRefs.get(i) + "%");
            }
            sql.append(")");
            query(sections, "reconciliation-service", "reconciliation_batches", sql.toString(), args.toArray());
        }

        // ⑦ 账本：postings.source_id 是账本内部口径（数值），由各单据主键反查，再取分录
        List<Object> paymentIds = ids(payments, "id");
        List<Object> postingArgs = new ArrayList<>();
        StringBuilder postingSql = new StringBuilder("SELECT * FROM ledger.postings WHERE ");
        List<String> clauses = new ArrayList<>();
        if (!paymentIds.isEmpty()) {
            clauses.add("(source_type = 'PAYMENT' AND source_id IN (" + placeholders(paymentIds) + "))");
            postingArgs.addAll(paymentIds);
        }
        if (!refundIds.isEmpty()) {
            clauses.add("(source_type = 'REFUND' AND source_id IN (" + placeholders(refundIds) + "))");
            postingArgs.addAll(refundIds);
        }
        if (!batchIds.isEmpty()) {
            clauses.add("(source_type = 'SETTLEMENT' AND source_id IN (" + placeholders(batchIds) + "))");
            postingArgs.addAll(batchIds);
        }
        if (clauses.isEmpty()) {
            sections.add(emptySection("ledger-service", "postings",
                    "（无 PAYMENT/REFUND/SETTLEMENT 来源可查 —— 尚无记账）"));
        } else {
            postingSql.append(String.join(" OR ", clauses));
            List<Map<String, Object>> postings = query(sections, "ledger-service", "postings",
                    postingSql.toString(), postingArgs.toArray());
            List<Object> postingIds = ids(postings, "id");
            query(sections, "ledger-service", "ledger_entries",
                    "SELECT * FROM ledger.ledger_entries WHERE posting_id IN (" + placeholders(postingIds) + ")",
                    postingIds.toArray());
        }
        return resp;
    }

    /** 单查封装：异常兜底为该 section 的 error，行值做可读化（Date→字符串）。 */
    private List<Map<String, Object>> query(List<Map<String, Object>> sections, String system,
                                            String table, String sql, Object[] args) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("system", system);
        section.put("table", table);
        section.put("sql", sql);
        sections.add(section);
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
            List<Map<String, Object>> clean = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> c = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    c.put(e.getKey(), e.getValue() instanceof Date d
                            ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d)
                            : e.getValue());
                }
                clean.add(c);
            }
            section.put("rows", clean);
            return rows;
        } catch (Exception ex) {
            section.put("rows", List.of());
            section.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> emptySection(String system, String table, String note) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("system", system);
        section.put("table", table);
        section.put("rows", List.of());
        section.put("note", note);
        return section;
    }

    private Map<String, Object> errorSection(String system, String table, String error) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("system", system);
        section.put("table", table);
        section.put("rows", List.of());
        section.put("error", error);
        return section;
    }

    private List<Object> ids(List<Map<String, Object>> rows, String column) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get(column);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private String placeholders(List<Object> values) {
        if (values.isEmpty()) {
            return "NULL";
        }
        return String.join(",", java.util.Collections.nCopies(values.size(), "?"));
    }
}
