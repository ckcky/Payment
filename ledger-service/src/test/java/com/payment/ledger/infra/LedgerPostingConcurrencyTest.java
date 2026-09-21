package com.payment.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.payment.testinfra.RealMysqlTestSupport;
import com.payment.testinfra.SchemaBootstrap;

/**
 * 并发记账的**真库**验证（spec 030 / T11，FR-223 / SC-B1-03 / SC-B1-04）。
 *
 * <h3>为什么必须是真库</h3>
 * 既有 {@code PostingIdempotencyTest} 用「首次 save 抛 {@code DuplicateKeyException}」的仓储桩
 * **确定性模拟**撞键——它验证的是「撞键后服务回查首次结果」这条应用逻辑，
 * 但「并发下真的只会有一个赢家」这件事，桩是证不了的：那是
 * {@code uk_postings_idempotency_key} 唯一约束的职责。本类用 Testcontainers-MySQL
 * 跑真实 DDL + 真实并发 INSERT，把这条兜底**实测**出来（spec 033 §3 升级判据：此类断言
 * MUST NOT 停留在 H2 或桩上，MUST 落 L2b）。
 *
 * <h3>载体迁移（spec 033 / T12，ADR-0081 决策 1 的「泛化为基座」）</h3>
 * 本类原是全仓唯一真库测试，容器生命周期与 DDL 装配为手搓特例（自建 {@code MySQLContainer}、
 * 手工剥 {@code CREATE DATABASE}/{@code USE}）。033 把该模式抽成共享基座
 * {@code deployment/test-infra}（{@link RealMysqlTestSupport} + {@link SchemaBootstrap}），
 * 本类迁到基座上：<b>三条用例与全部断言原样保留</b>，变化的只有载体——
 * DDL 改由 {@code deployment/schema/09-ledger-schema.sql} 单一来源装配（C-1），
 * 容器跨类复用 + 类内独占库（C-2），无 Docker 时按基座契约整体 skip（C-4）。
 * （原版 {@code @BeforeAll}/{@code @AfterAll} 手搓段与 DDL 读取段随之退役，由基座承接。）
 *
 * <h3>无 Docker 时的行为</h3>
 * 基座 {@code DockerContract}：本地 skip（WARN 可见）；CI 真库层 job 设
 * {@code -Drealdb.required=true} 使 skip 即红（spec 033 §13.3 禁假绿①）。
 */
class LedgerPostingConcurrencyTest extends RealMysqlTestSupport {

    /** 并发线程数：足够多以保证真实撞键，又不至于把容器压垮。 */
    private static final int CONCURRENCY = 8;

    @Override
    protected List<SchemaBootstrap.Script> schemaScripts() {
        // 09-ledger-schema.sql 的幂等唯一约束一旦被改名或删除，本类立刻红——
        // 避免测试与真实 schema 各说各话（禁假绿②；原手搓版同一断言的基座化形态）。
        return List.of(new SchemaBootstrap.Script("09-ledger-schema.sql", "uk_postings_idempotency_key"));
    }

    // ---------- 用例（断言与 030/T11 原版逐字一致，仅载体迁移） ----------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("并发 N 条同幂等键记账 ⇒ 唯一约束吸收，落库恰好 1 条 [FR-223][SC-B1-03][SC-B1-04]")
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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("先同步成功再收到回调（顺序两次同键）⇒ 落库恰好 1 条 [FR-223][SC-B1-03]")
    void sequentialSyncThenCallbackPostingIsSingle() throws Exception {
        String key = "PAYMENT:PM-T11-SEQ-" + System.nanoTime();

        boolean first = insertPosting("LP-T11-SEQ-A", key);
        boolean second = insertPosting("LP-T11-SEQ-B", key);

        assertThat(first).as("同步成功路径先落库").isTrue();
        assertThat(second).as("回调路径撞唯一键，被吸收").isFalse();
        assertThat(countPostings(key)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("不同幂等键互不干扰（约束没有误伤）[SC-B1-04]")
    void distinctKeysAreAllPersisted() throws Exception {
        String a = "PAYMENT:PM-T11-A-" + System.nanoTime();
        String b = "PAYMENT:PM-T11-B-" + System.nanoTime();

        assertThat(insertPosting("LP-T11-DA", a)).isTrue();
        assertThat(insertPosting("LP-T11-DB", b)).isTrue();

        assertThat(countPostings(a)).isEqualTo(1);
        assertThat(countPostings(b)).isEqualTo(1);
    }

    // ---------- 支撑 ----------

    /**
     * 插入一条记账交易；撞唯一键返回 {@code false}（被吸收），成功返回 {@code true}。
     * {@code openConnection()} 每次新建连接、无共享状态，从并发线程调用安全。
     */
    private boolean insertPosting(String postingNo, String idempotencyKey) throws SQLException {
        // 031 形态：event_type/period/posted_at 均 NOT NULL（幂等键为账本派生 {eventType}:{sourceId}）
        String sql = "INSERT INTO postings (posting_no, event_type, idempotency_key, source_type, "
                + "source_id, status, currency, period, posted_at, created_at, updated_at, version) "
                + "VALUES (?, 'PAYMENT_CAPTURE', ?, 'PAYMENT', ?, 'POSTED', 'CNY', "
                + "DATE_FORMAT(NOW(), '%Y-%m'), NOW(), NOW(), NOW(), 1)";
        try (Connection c = openConnection();
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

    private int countPostings(String idempotencyKey) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM postings WHERE idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
