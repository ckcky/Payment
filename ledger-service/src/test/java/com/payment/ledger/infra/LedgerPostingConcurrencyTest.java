package com.payment.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 并发记账的**真库**验证（spec 030 / T11，FR-223 / SC-B1-03 / SC-B1-04）。
 *
 * <h3>为什么必须是真库</h3>
 * 既有 {@code LedgerIdempotencyTest} 用「首次 save 抛 {@code DuplicateKeyException}」的仓储桩
 * **确定性模拟**撞键——它验证的是「撞键后服务回查首次结果」这条应用逻辑，
 * 但「并发下真的只会有一个赢家」这件事，桩是证不了的：那是
 * {@code uk_postings_idempotency_key} 唯一约束的职责。本类用 Testcontainers-MySQL
 * 跑真实 DDL + 真实并发 INSERT，把这条兜底**实测**出来。
 *
 * <h3>无 Docker 时的行为</h3>
 * {@code @BeforeAll} 里先 {@code assumeTrue(DockerClientFactory.instance().isDockerAvailable())}：
 * 没有 Docker 守护进程 ⇒ 本类整体 **skip 而非 fail**（T131 裁决：并发半部需真库，
 * 不得让 766+ 的既有门禁在无 Docker 环境假红）。
 *
 * <h3>DDL 来源</h3>
 * 直接读 {@code deployment/schema/09-ledger-schema.sql}（去掉 {@code CREATE DATABASE}/{@code USE}），
 * 并断言其中存在 {@code uk_postings_idempotency_key}——约束一旦被改名或删除，本类立刻红，
 * 避免测试与真实 schema 各说各话。
 */
class LedgerPostingConcurrencyTest {

    /** 并发线程数：足够多以保证真实撞键，又不至于把容器压垮。 */
    private static final int CONCURRENCY = 8;

    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startDatabase() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Testcontainers 需要可用的 Docker 守护进程；本机未运行 Docker Desktop 时本类整体跳过"
                        + "（spec 030 / T131：并发半部需真库，无 Docker 不得让门禁假红）");

        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger");
        mysql.start();
        applySchema();
    }

    @AfterAll
    static void stopDatabase() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("并发 N 条同幂等键记账 ⇒ 唯一约束吸收，落库恰好 1 条 [FR-223][SC-B1-03][SC-B1-04]")
    void concurrentSameKeyPostingsCollapseToOne() throws Exception {
        String key = "PAYMENT:PM-T11-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        // 起跑线：让所有线程尽量同时发枪，最大化撞键概率
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                final String postingNo = "LP-T11-" + i;
                futures.add(pool.submit(() -> {
                    gun.await(5, TimeUnit.SECONDS);
                    return insertPosting(postingNo, key);
                }));
            }
            gun.countDown();

            int winners = 0;
            int absorbed = 0;
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(30, TimeUnit.SECONDS))) {
                    winners++;
                } else {
                    absorbed++;
                }
            }

            assertThat(winners).as("并发下必须恰好一个赢家").isEqualTo(1);
            assertThat(absorbed).as("其余全部被唯一约束吸收").isEqualTo(CONCURRENCY - 1);
            assertThat(countPostings(key)).as("落库记账批次数（T11 的『分录数 = 1』）").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("先同步成功再收到回调（顺序两次同键）⇒ 落库恰好 1 条 [FR-223][SC-B1-03]")
    void sequentialSyncThenCallbackPostingIsSingle() throws Exception {
        String key = "PAYMENT:PM-T11-SEQ-" + System.nanoTime();

        boolean first = insertPosting("LP-T11-SEQ-A", key);
        boolean second = insertPosting("LP-T11-SEQ-B", key);

        assertThat(first).as("同步成功路径先落库").isTrue();
        assertThat(second).as("回调路径撞唯一键，被吸收").isFalse();
        assertThat(countPostings(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("不同幂等键互不干扰（约束没有误伤）[SC-B1-04]")
    void distinctKeysAreAllPersisted() throws Exception {
        String a = "PAYMENT:PM-T11-A-" + System.nanoTime();
        String b = "PAYMENT:PM-T11-B-" + System.nanoTime();

        assertThat(insertPosting("LP-T11-DA", a)).isTrue();
        assertThat(insertPosting("LP-T11-DB", b)).isTrue();

        assertThat(countPostings(a)).isEqualTo(1);
        assertThat(countPostings(b)).isEqualTo(1);
    }

    // ---------- 支撑 ----------

    /** 插入一条记账批次；撞唯一键返回 {@code false}（被吸收），成功返回 {@code true}。 */
    private static boolean insertPosting(String postingNo, String idempotencyKey) throws SQLException {
        String sql = "INSERT INTO postings (posting_no, idempotency_key, source_type, source_id, "
                + "status, currency, created_at, updated_at, version) "
                + "VALUES (?, ?, 'PAYMENT', ?, 'POSTED', 'CNY', NOW(), NOW(), 1)";
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, postingNo);
            ps.setString(2, idempotencyKey);
            ps.setString(3, "pay-" + postingNo);
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException absorbed) {
            return false;
        }
    }

    private static int countPostings(String idempotencyKey) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM postings WHERE idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "ledger", "ledger");
    }

    /** 多语句执行需要 {@code allowMultiQueries=true}（schema 脚本含多条 DDL/DML）。 */
    private static String jdbcUrl() {
        String url = mysql.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "allowMultiQueries=true";
    }

    private static void applySchema() throws Exception {
        String sql = Files.readString(resolveSchemaFile(), StandardCharsets.UTF_8)
                .lines()
                .filter(l -> !l.trim().toUpperCase(Locale.ROOT).startsWith("CREATE DATABASE"))
                .filter(l -> !l.trim().toUpperCase(Locale.ROOT).startsWith("USE "))
                .collect(Collectors.joining("\n"));

        assertThat(sql)
                .as("真实 schema 必须保留幂等唯一约束；约束改名/删除则本测试必须立刻失败")
                .contains("uk_postings_idempotency_key");

        try (Connection c = newConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Surefire 工作目录为本模块目录（仓库根/{@code ledger-service}），schema 在同级 {@code deployment/}。 */
    private static Path resolveSchemaFile() {
        Path fromModule = Paths.get("..", "deployment", "schema", "09-ledger-schema.sql");
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Paths.get("deployment", "schema", "09-ledger-schema.sql");
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("找不到 ledger schema DDL：" + fromModule.toAbsolutePath());
    }
}
