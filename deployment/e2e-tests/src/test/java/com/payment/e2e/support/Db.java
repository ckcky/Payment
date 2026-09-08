package com.payment.e2e.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 多 schema JDBC 探针（spec 022 / T406，FR-004）：
 * 按业务 schema（order/payment/fulfillment/entitlement/ledger/settlement/reconciliation/
 * catalog/merchant）注册独立连接，供聚合不变量断言与故障注入（测试环境 DB 直改）。
 */
public final class Db implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Connection> connections = new LinkedHashMap<>();

    /** 执行只读查询，返回行列表（列名 → 值）。 */
    public List<Map<String, Object>> query(String schema, String sql) {
        try {
            Connection conn = connection(schema);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DB query failed [" + schema + "] " + sql + ": " + e.getMessage(), e);
        }
    }

    /** 单值便捷读取（如 COUNT/SUM），空结果返回 null。 */
    public Object scalar(String schema, String sql) {
        List<Map<String, Object>> rows = query(schema, sql);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0).values().iterator().next();
    }

    /**
     * 写操作（UPDATE/INSERT/DELETE/DDL）——仅用于故障注入（测试环境 DB 直改，plan §5）。
     *
     * <p>经 mock-channel-web 的 /demo/db-exec 代执行：测试 JVM 发出的 JDBC 写在本机沙箱
     * 环境下被间歇性吞掉（语句未达 MySQL 却返回成功，v9l/v9o general_log 实证——同连接
     * SELECT 正常、独立 JVM 的 DELETE 正常、测试 JVM 的 DELETE/UPDATE 丢失），而服务进程
     * 的写通道全程可靠；注入写的关键要求是「确定落库」，故统一走服务端代执行。</p>
     */
    public int execute(String schema, String sql) {
        try {
            String body = MAPPER.writeValueAsString(java.util.Map.of("schema", schema, "sql", sql));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Env.serviceUrl("mock") + "/demo/db-exec"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode json = MAPPER.readTree(resp.body());
            if (resp.statusCode() != 200 || json.hasNonNull("error")) {
                throw new IllegalStateException("db-exec failed [" + schema + "] " + sql
                        + ": HTTP " + resp.statusCode() + " " + resp.body());
            }
            return json.path("affected").asInt(-1);
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("db-exec io failed [" + schema + "] " + sql
                    + ": " + e.getMessage(), e);
        }
    }

    private synchronized Connection connection(String schema) throws SQLException {
        Connection conn = connections.get(schema);
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(Env.jdbcUrl(schema), Env.dbUser(), Env.dbPassword());
            // 强制自动提交：故障注入的写操作必须立即对其他连接（recon→ledger 审计读）可见，
            // 显式声明以防驱动/连接复用路径上的非自动提交状态残留
            conn.setAutoCommit(true);
            connections.put(schema, conn);
        }
        return conn;
    }

    @Override
    public void close() {
        for (Connection c : connections.values()) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 关闭失败不影响断言结论
            }
        }
        connections.clear();
    }
}
