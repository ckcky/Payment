package com.payment.mockchannel.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

/**
 * 演示 / E2E 测试支持：代执行单条写 SQL（UPDATE/INSERT/DELETE/DDL）。
 *
 * <p>用途：E2E 故障注入需要直改业务库（plan §5「测试环境 DB 直改」）。测试 JVM 发出的
 * JDBC 写在本机沙箱环境下被间歇性吞掉（语句未达 MySQL 却返回成功，v9l/v9o general_log
 * 实证），而服务进程的写通道全程可靠——故注入写统一经本端点由本服务代执行。</p>
 *
 * <p>边界：本服务为演示组件（非生产，ADR 见 docs）；仅绑定内网端口，且只接受单条写语句
 * （executeUpdate），不提供查询（查询走测试自身的 JDBC 读）。</p>
 */
@RestController
@RequestMapping("/demo")
public class DemoDbExecController {

    private final DataSource dataSource;

    public DemoDbExecController(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider.getIfAvailable();
    }

    /** body: {"schema": "ledger", "sql": "UPDATE ..."}；返回 {"affected": n}。 */
    @PostMapping("/db-exec")
    public Map<String, Object> exec(@RequestBody Map<String, String> body) {
        if (dataSource == null) {
            return Map.of("error", "DataSource 不可用（依赖缺失）");
        }
        String schema = body.get("schema");
        String sql = body.get("sql");
        if (schema == null || schema.isBlank() || sql == null || sql.isBlank()) {
            return Map.of("error", "schema/sql 必填");
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setCatalog(schema);
            try (Statement st = conn.createStatement()) {
                int affected = st.executeUpdate(sql);
                return Map.of("affected", affected);
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
