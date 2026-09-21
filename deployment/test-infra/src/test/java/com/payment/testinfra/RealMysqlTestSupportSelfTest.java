package com.payment.testinfra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 基座能力自证（spec 033 §0「能证伪的能力」；§5.3 T1 的<b>落点可用性</b>证明）：
 * 在中性合成表 {@code t_probe_idem} 上验证「真 DDL 唯一约束 + 并发撞键」这条
 * H2/桩结构性证不了的能力，经由本基座真实可达。
 *
 * <p><b>归属红线（spec 033 §4）</b>：本类是基础设施自证，不是业务用例——
 * 表、键、断言全部中性；T1-a/T1-b/T1-c 的业务用例本体归 031/032/payment 域。</p>
 */
class RealMysqlTestSupportSelfTest extends RealMysqlTestSupport {

    private static final String TABLE = "t_probe_idem";
    private static final int CONCURRENCY = 8;

    @Override
    protected void onDatabaseReady() {
        try (Connection c = openConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "probe_no VARCHAR(64) NOT NULL,"
                    + "idem_key VARCHAR(64) NOT NULL,"
                    + "created_at DATETIME NOT NULL,"
                    + "PRIMARY KEY (probe_no),"
                    + "UNIQUE KEY uk_probe_idem (idem_key)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (SQLException e) {
            throw new IllegalStateException("合成探针表创建失败", e);
        }
    }

    @Test
    @DisplayName("类内独占库生效：DATABASE() 即本类专属库名 [C-2]")
    void perClassDatabaseIsExclusive() throws Exception {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT DATABASE()")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(databaseName());
            }
        }
    }

    @Test
    @DisplayName("并发同键写入 ⇒ 唯一约束吸收，恰好 1 行（基座能承载 T1 类断言的自证）")
    void concurrentSameKeyInsertsCollapseToOne() throws Exception {
        String key = "probe-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                final String probeNo = "P-" + i;
                futures.add(pool.submit(() -> {
                    gun.await(5, TimeUnit.SECONDS);
                    return insertProbe(probeNo, key);
                }));
            }
            gun.countDown();

            int winners = 0;
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(30, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            assertThat(winners).as("并发下必须恰好一个赢家").isEqualTo(1);
            assertThat(countByKey(key)).as("落库行数").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("不同键互不误伤（约束没有过收）")
    void distinctKeysAllPersist() throws Exception {
        String a = "probe-a-" + System.nanoTime();
        String b = "probe-b-" + System.nanoTime();
        assertThat(insertProbe("PA", a)).isTrue();
        assertThat(insertProbe("PB", b)).isTrue();
        assertThat(countByKey(a)).isEqualTo(1);
        assertThat(countByKey(b)).isEqualTo(1);
    }

    // ---------- 支撑 ----------

    private boolean insertProbe(String probeNo, String idemKey) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + TABLE + " (probe_no, idem_key, created_at) VALUES (?, ?, NOW())")) {
            ps.setString(1, probeNo);
            ps.setString(2, idemKey);
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException absorbed) {
            return false;
        }
    }

    private int countByKey(String idemKey) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + TABLE + " WHERE idem_key = ?")) {
            ps.setString(1, idemKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
