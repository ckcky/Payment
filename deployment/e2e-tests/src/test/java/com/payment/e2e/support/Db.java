package com.payment.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 schema JDBC 探针（spec 022 / T406，FR-004）：
 * 按业务 schema（order/payment/fulfillment/entitlement/ledger/settlement/reconciliation/
 * catalog/merchant）注册独立连接，供聚合不变量断言与故障注入（测试环境 DB 直改）。
 */
public final class Db implements AutoCloseable {

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

    /** 写操作（UPDATE/INSERT/DELETE）——仅用于故障注入（测试环境 DB 直改，plan §5）。 */
    public int execute(String schema, String sql) {
        try {
            Connection conn = connection(schema);
            try (Statement st = conn.createStatement()) {
                return st.executeUpdate(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DB execute failed [" + schema + "] " + sql + ": " + e.getMessage(), e);
        }
    }

    private synchronized Connection connection(String schema) throws SQLException {
        Connection conn = connections.get(schema);
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(Env.jdbcUrl(schema), Env.dbUser(), Env.dbPassword());
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
