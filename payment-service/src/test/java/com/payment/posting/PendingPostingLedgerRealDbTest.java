package com.payment.posting;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.payment.infra.client.LedgerFeignClient;
import com.payment.posting.application.PostingEventTypes;
import com.payment.posting.application.PostingPendingRecorder;
import com.payment.posting.application.PostingProperties;
import com.payment.posting.application.PostingRetryScheduler;
import com.payment.posting.domain.PendingPosting;
import com.payment.posting.infra.PostingReplayDispatcher;
import com.payment.posting.infra.persistence.MybatisPendingPostingRepository;
import com.payment.posting.infra.persistence.PendingPostingMapper;
import com.payment.testinfra.RealMysqlTestSupport;
import com.payment.testinfra.SchemaBootstrap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TT-2 / TT-3 / TT-4（spec 034 §13，L2b 真库）：出站失败台账 pending_postings 的三个核心性质。
 *
 * <h3>为什么必须是真库</h3>
 * TT-2 的「同一事件反复失败 ⇒ 1 行」由 UNIQUE(event_type, source_id) 兜底——只有真 InnoDB
 * 能证明重复登记真的被唯一键挡住（内存桩的 Map 天然单行，证不了约束存在）。
 * TT-3 的「重放不双记」要求重放载荷与首次请求逐字节一致且幂等键恒定，DB 往返后仍成立。
 *
 * <h3>Ledger 幂等模拟</h3>
 * TT-3/TT-4 的目的地用「按 {eventType}:{sourceId} 派生键吸收重复」的 fake（031 §9 原则 10 的
 * 契约形状）：重放器发 5 次 ⇒ 受理 1 次、吸收 4 次——台账重放不产生第二套分录。
 *
 * <h3>装配与隔离</h3>
 * 非 Spring 容器测试：手工装配 MyBatis-Plus（对齐 TT-1 先例）；退避压到 1ms 使新登记行
 * 立即到期（表无 next_retry_at 列，到期判定 = updated_at + backoff(retry_count)）。
 * 各用例用独立 sourceId 隔离，断言一律按 sourceId 定位本用例行。无 Docker 时按基座契约
 * 整体 skip（CI real-db job 上 skip 即红）。
 */
class PendingPostingLedgerRealDbTest extends RealMysqlTestSupport {

    /**
     * 记账请求按用例构造：payload 内嵌的 sourceId 必须与登记行一致——重放是「原样重发」，
     * Ledger 按 payload 内的 {eventType}:{sourceId} 派生键吸收；各用例独立 sourceId 即各自独立幂等键。
     */
    private static AccountingEventRequest captureRequest(String sourceId) {
        return new AccountingEventRequest(
                "PAYMENT_CAPTURE", "PAYMENT", sourceId, "CNY",
                10_000L, 20L, 5L, "M001", "MOCK", 9_975L,
                null, null, null, null, null, null);
    }

    private PostingPendingRecorder recorder;
    private MybatisPendingPostingRepository repository;
    private IdempotentLedgerFake ledger;
    private PostingProperties postingProperties;

    @Override
    protected List<SchemaBootstrap.Script> schemaScripts() {
        // UNIQUE(event_type, source_id) 一旦被改名/删除，本类立刻红（禁假绿②）
        return List.of(new SchemaBootstrap.Script("03-payment-schema.sql", "uk_pending_postings_event_source"));
    }

    @BeforeAll
    void wirePostingStack() throws Exception {
        DataSource dataSource = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return openConnection();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return openConnection();
            }
        };
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        MybatisPlusInterceptor mpInterceptor = new MybatisPlusInterceptor();
        mpInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        mpInterceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factoryBean.setPlugins(mpInterceptor);
        factoryBean.setGlobalConfig(new GlobalConfig());
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        var configuration = (com.baomidou.mybatisplus.core.MybatisConfiguration) sqlSessionFactory.getConfiguration();
        configuration.addMapper(PendingPostingMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);

        repository = new MybatisPendingPostingRepository(template.getMapper(PendingPostingMapper.class));
        recorder = new PostingPendingRecorder(repository, new NoopBusinessMetrics());
        ledger = new IdempotentLedgerFake();
        // 退避 1ms：新登记行（updated_at=本秒）立即到期，retryRound 可即刻补投
        postingProperties = new PostingProperties();
        postingProperties.setRetryBackoff(List.of(Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    private PostingRetryScheduler scheduler() {
        return new PostingRetryScheduler(repository,
                new PostingReplayDispatcher(recorder, ledger, null),
                postingProperties, new NoopBusinessMetrics());
    }

    @Test
    @DisplayName("TT-2：同一事件反复失败 ⇒ 仍 1 行；补投失败 retry_count 递增；DB 唯一键兜底")
    void repeatedFailuresYieldSingleRowWithIncreasingRetryCount() throws SQLException {
        String sourceId = "PM-TT2";
        recorder.recordFailure(PostingEventTypes.PAYMENT_CAPTURE, "PAYMENT", sourceId,
                PostingEventTypes.PAYMENT_CAPTURE + ":" + sourceId, captureRequest(sourceId), "ledger down");

        // 同一事实反复失败：唯一键吸收，仍 1 行
        recorder.recordFailure(PostingEventTypes.PAYMENT_CAPTURE, "PAYMENT", sourceId,
                PostingEventTypes.PAYMENT_CAPTURE + ":" + sourceId, captureRequest(sourceId), "ledger down again");
        recorder.recordFailure(PostingEventTypes.PAYMENT_CAPTURE, "PAYMENT", sourceId,
                PostingEventTypes.PAYMENT_CAPTURE + ":" + sourceId, captureRequest(sourceId), "ledger down 3rd");

        assertThat(countRows(sourceId))
                .as("UNIQUE(event_type, source_id) 兜底：反复失败只 1 行").isEqualTo(1L);
        PendingPosting row = rowBySource(sourceId);
        assertThat(row.getRetryCount()).as("登记口径 retry_count=1（首次已失败的投递）").isEqualTo(1);

        // 绕过 recorder 的吸收逻辑直接插重复键：DB 唯一约束真的存在（不是先查后插的假象）
        assertThatThrownBy(() -> repository.insert(new PendingPosting(
                        PostingEventTypes.PAYMENT_CAPTURE, "PAYMENT", sourceId, "k", "{}")))
                .isInstanceOf(DuplicateKeyException.class);

        // 补投失败：retry_count 递增（重放器持续失败，走真实调度器轮次）
        ledger.failing = true;
        scheduler().retryRound();
        assertThat(rowBySource(sourceId).getRetryCount()).as("一轮失败后 retry_count=2").isEqualTo(2);
        scheduler().retryRound();
        assertThat(rowBySource(sourceId).getRetryCount()).as("两轮失败后 retry_count=3").isEqualTo(3);
    }

    @Test
    @DisplayName("TT-3：台账重放 5 次 ⇒ Ledger 按派生键吸收，只 1 套分录；载荷逐字节一致")
    void fiveReplaysProduceSingleLedgerFact() throws SQLException {
        String sourceId = "PM-TT3";
        ledger.failing = false; // 用例间共享 fake：不依赖执行顺序（TT-2 可能已置 failing）
        recorder.recordFailure(PostingEventTypes.PAYMENT_CAPTURE, "PAYMENT", sourceId,
                PostingEventTypes.PAYMENT_CAPTURE + ":" + sourceId, captureRequest(sourceId), "first failure");

        PendingPosting row = rowBySource(sourceId);
        String originalPayload = row.getPayloadJson();
        PostingReplayDispatcher dispatcher = new PostingReplayDispatcher(recorder, ledger, null);

        for (int i = 1; i <= 5; i++) {
            assertThat(dispatcher.replay(row)).as("第 %d 次重放被 Ledger 受理（幂等）", i).isTrue();
            assertThat(ledger.acceptCountForKey(keyOf(sourceId)))
                    .as("第 %d 次重放后该事实仍只 1 套分录", i).isEqualTo(1);
        }

        assertThat(ledger.replaysForKey(keyOf(sourceId))).as("5 次重投全部发出").isEqualTo(5);
        assertThat(ledger.payloadsForKey(keyOf(sourceId)))
                .as("每次重放载荷与首次逐字节一致（零改写）").containsOnly(originalPayload);
    }

    @Test
    @DisplayName("TT-4：ABANDONED 后人工 replay 可再成功，且不产生第二事实")
    void abandonedRowManualReplaySucceedsWithoutSecondFact() throws SQLException {
        String sourceId = "PM-TT4";
        recorder.recordFailure(PostingEventTypes.PAYMENT_CAPTURE, "PAYMENT", sourceId,
                PostingEventTypes.PAYMENT_CAPTURE + ":" + sourceId, captureRequest(sourceId), "first failure");

        // 自动补投 5 轮全败：retry_count 1→6，置 ABANDONED（终态，退出自动补投）
        ledger.failing = true;
        for (int i = 0; i < 5; i++) {
            scheduler().retryRound();
        }
        PendingPosting abandoned = rowBySource(sourceId);
        assertThat(abandoned.getStatus()).as("耗尽 ⇒ ABANDONED").isEqualTo(PendingPosting.PostingStatus.ABANDONED);
        assertThat(abandoned.getRetryCount()).as("首次登记 + 5 次补投全败 = 6").isEqualTo(6);

        // 自动补投不再触达 ABANDONED 行：恢复 Ledger 后轮次不改变该行
        ledger.failing = false;
        scheduler().retryRound();
        PendingPosting untouched = rowBySource(sourceId);
        assertThat(untouched.getStatus()).as("终态行不被自动补投改写").isEqualTo(PendingPosting.PostingStatus.ABANDONED);
        assertThat(untouched.getRetryCount()).as("耗尽计数不漂移").isEqualTo(6);

        // 人工 replay：重置回 PENDING（retry_count 归 1）→ 补投成功 → REPOSTED（终态保留供审计）
        untouched.resetForManualReplay("manual replay re-armed");
        repository.update(untouched);
        scheduler().retryRound();

        PendingPosting reposted = rowBySource(sourceId);
        assertThat(reposted.getStatus()).isEqualTo(PendingPosting.PostingStatus.REPOSTED);
        assertThat(reposted.getFailReason()).as("成功后失败原因清空").isNull();

        // 不产生第二事实：同一 sourceId 全程只 1 行、Ledger 该事实只 1 套分录
        assertThat(countRows(sourceId)).as("同 sourceId 全程只 1 行").isEqualTo(1L);
        assertThat(ledger.acceptCountForKey(keyOf(sourceId)))
                .as("ABANDONED→人工 replay→REPOSTED 不产生第二套分录").isEqualTo(1);
    }

    // ---------- 按用例隔离的查询 ----------

    private String keyOf(String sourceId) {
        return PostingEventTypes.PAYMENT_CAPTURE + ":" + sourceId;
    }

    private long countRows(String sourceId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM pending_postings WHERE event_type = ? AND source_id = ?")) {
            ps.setString(1, PostingEventTypes.PAYMENT_CAPTURE);
            ps.setString(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private PendingPosting rowBySource(String sourceId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM pending_postings WHERE event_type = ? AND source_id = ?")) {
            ps.setString(1, PostingEventTypes.PAYMENT_CAPTURE);
            ps.setString(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return repository.findById(rs.getLong(1)).orElseThrow();
            }
        }
    }

    /**
     * Ledger 幂等模拟（031 §9 原则 10 的契约形状）：按 {eventType}:{sourceId} 派生键吸收重复，
     * 每键受理恰 1 次。failing=true 模拟 Ledger 不可用（抛异常，驱动补投失败计数）。
     * receivedPayloads 记录「反序列化后重发请求的再序列化 JSON」——与登记时落库的 payload_json
     * 逐字节相等即证明重放零改写（不重算键、不重构请求）。
     */
    static final class IdempotentLedgerFake implements LedgerFeignClient {

        private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        final Set<String> appliedKeys = ConcurrentHashMap.newKeySet();
        final Map<String, List<String>> receivedPayloads = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> acceptCounts = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> replayCounts = new ConcurrentHashMap<>();
        volatile boolean failing = false;

        int acceptCountForKey(String key) {
            AtomicInteger c = acceptCounts.get(key);
            return c == null ? 0 : c.get();
        }

        int replaysForKey(String key) {
            AtomicInteger c = replayCounts.get(key);
            return c == null ? 0 : c.get();
        }

        List<String> payloadsForKey(String key) {
            List<String> list = receivedPayloads.get(key);
            return list == null ? List.of() : List.copyOf(list);
        }

        @Override
        public AccountingEventResponse postEvent(AccountingEventRequest request) {
            if (failing) {
                throw new IllegalStateException("ledger unavailable (simulated)");
            }
            String key = request.eventType() + ":" + request.sourceId();
            replayCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            try {
                receivedPayloads.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(PAYLOAD_MAPPER.writeValueAsString(request));
            } catch (Exception e) {
                throw new IllegalStateException("重放载荷再序列化失败", e);
            }
            if (appliedKeys.add(key)) {
                acceptCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            }
            return new AccountingEventResponse(1L, "PT-1", request.eventType(), key,
                    request.sourceType(), request.sourceId(), request.currency(), "2026-09", null,
                    "ACCEPTED", List.of());
        }

        @Override
        public AccountingEventResponse find(String eventType, String sourceId) {
            // 补偿核对入口：fake 不承载回查语义（TT-2~4 只钉补投路径）
            return null;
        }
    }
}
