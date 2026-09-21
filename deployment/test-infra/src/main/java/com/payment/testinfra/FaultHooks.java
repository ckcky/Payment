package com.payment.testinfra;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 真库级故障注入钩子（spec 033 §10 F-2，供 034 的用例本体复用；基座只提供机制）。
 *
 * <p>F-2 覆盖三类真库才成立的事实：事务边界（连接被杀 ⇒ 未提交即回滚）、
 * 锁竞争（慢查询拖住行锁）、约束冲突（由真 DDL 生效，不需要钩子——并发撞键见
 * {@code RealMysqlTestSupport} 的自证测试与各业务 Feature 的 L2b 用例）。</p>
 *
 * <p>命名与实现刻意不含任何业务语义（C-5）。</p>
 */
public final class FaultHooks {

    private FaultHooks() {
    }

    /** 慢查询：在同一连接上阻塞指定秒数（可拖住该连接已持有的行锁，制造锁竞争窗口）。 */
    public static void slowQuerySeconds(Connection connection, int seconds) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT SLEEP(?)")) {
            ps.setInt(1, seconds);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    /**
     * 杀掉目标连接（服务端视角的连接中断）： victim 上未提交的事务由 InnoDB 按回滚语义清理——
     * 「后置动作失败不得回滚前序事实」类断言的真库验证入口。
     * {@code adminConnection} 用于下发 KILL，可与 victim 同源（自 KILL 合法）。
     */
    public static void killConnection(Connection victim, Connection adminConnection) throws SQLException {
        long victimId;
        try (Statement statement = victim.createStatement();
             ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
            rs.next();
            victimId = rs.getLong(1);
        }
        try (Statement statement = adminConnection.createStatement()) {
            statement.execute("KILL " + victimId);
        }
    }

    /** 收紧/放宽本连接的 InnoDB 锁等待超时（秒）——模拟锁竞争下「等 or 快速失败」两种行为面。 */
    public static void setInnodbLockWaitTimeoutSeconds(Connection connection, int seconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET innodb_lock_wait_timeout = " + seconds);
        }
    }
}
