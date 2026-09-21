package com.payment.testinfra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FaultHooks 自证（F-2 机制可用性）：慢查询真实阻塞、KILL 后连接失效——
 * 机制自证，不涉及任何业务语义（C-5）。
 */
class FaultHooksSelfTest extends RealMysqlTestSupport {

    @Test
    @DisplayName("slowQuerySeconds 真实阻塞指定秒数（锁竞争窗口可制造）")
    void slowQueryBlocksForRequestedDuration() throws Exception {
        long start = System.nanoTime();
        try (Connection c = openConnection()) {
            FaultHooks.slowQuerySeconds(c, 1);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).as("SLEEP(1) 必须真实阻塞 ≥1s").isGreaterThanOrEqualTo(1000L);
    }

    @Test
    @DisplayName("killConnection 后目标连接不可再用（事务中断语义的入口）")
    void killedConnectionIsUnusable() throws Exception {
        try (Connection victim = openConnection();
             Connection admin = openConnection()) {
            FaultHooks.killConnection(victim, admin);
            assertThat(victim.isValid(2))
                    .as("被 KILL 的连接必须失效（未提交事务由服务端回滚）")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("innodb_lock_wait_timeout 可按连接覆盖")
    void lockWaitTimeoutIsPerConnectionSettable() throws Exception {
        try (Connection c = openConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT @@innodb_lock_wait_timeout")) {
            FaultHooks.setInnodbLockWaitTimeoutSeconds(c, 7);
            try (var after = c.createStatement();
                 var rs2 = after.executeQuery("SELECT @@innodb_lock_wait_timeout")) {
                rs.next();
                rs2.next();
                assertThat(rs2.getInt(1)).isEqualTo(7);
            }
        }
    }
}
