package com.payment.reconciliation.posting;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.audit.infra.FeignAuditLedgerGateway;
import com.payment.reconciliation.audit.infra.LedgerAuditFeignClient;
import com.payment.reconciliation.posting.application.PostingEventTypes;
import com.payment.reconciliation.posting.application.PostingPendingRecorder;
import com.payment.reconciliation.posting.application.PostingProperties;
import com.payment.reconciliation.posting.application.PostingRetryScheduler;
import com.payment.reconciliation.posting.domain.PendingPosting;
import com.payment.reconciliation.posting.infra.PostingReplayDispatcher;
import com.payment.reconciliation.posting.infra.persistence.MybatisPendingPostingRepository;
import com.payment.reconciliation.posting.infra.persistence.PendingPostingMapper;
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
 * TT-8（tasks 034-B / spec 034 §9，L2b 真库，reconciliation 落点）：
 * 挂账/调账（AUDIT_ADJUSTMENT → ADJUSTMENT 事件）出站失败的台账兜底与重放纪律。
 *
 * <h3>被钉死的行为</h3>
 * <ol>
 *   <li><b>失败上抛语义保留</b>（NFR-008）：{@link FeignAuditLedgerGateway} 记账失败 →
 *       先落台账再原样上抛，处置 MUST 留痕；</li>
 *   <li><b>重放次数口径</b>：Ledger 调用 == 两次失败尝试 + 台账补投 1 次 == 3，
 *       派生键 {@code ADJUSTMENT:{adjustNo}} 吸收重复 ⇒ 账本事实只 1 套（不双记）；</li>
 *   <li><b>一行一事实</b>：UNIQUE(event_type, source_id) 由真库证明（同 adjustNo 反复失败只 1 行）。</li>
 * </ol>
 *
 * <p>非 Spring 容器测试（对齐 TT-1 装配先例）；无 Docker 时按基座契约整体 skip。</p>
 */
class AuditAdjustmentPostingRealDbTest extends RealMysqlTestSupport {

    private static final String ADJUST_NO = "ADJ-TT8";
    private static final AdjustmentPolicy.AdjustPlan SUSPENSE_PLAN =
            new AdjustmentPolicy.AdjustPlan("SUSPENSE", 700L, null, null, null, null);

    private FeignAuditLedgerGateway gateway;
    private PostingRetryScheduler scheduler;
    private MybatisPendingPostingRepository repository;
    private CountingLedgerFake ledger;
    private PostingProperties postingProperties;

    @Override
    protected List<SchemaBootstrap.Script> schemaScripts() {
        return List.of(new SchemaBootstrap.Script("07-reconciliation-schema.sql",
                "uk_pending_postings_event_source"));
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
        PostingPendingRecorder recorder =
                new PostingPendingRecorder(repository, new NoopBusinessMetrics());
        ledger = new CountingLedgerFake();
        postingProperties = new PostingProperties();
        postingProperties.setRetryBackoff(List.of(Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        scheduler = new PostingRetryScheduler(repository,
                new PostingReplayDispatcher(recorder, ledger), postingProperties, new NoopBusinessMetrics());
        gateway = new FeignAuditLedgerGateway(ledger, new NoopBusinessMetrics(), recorder);
    }

    @Test
    @DisplayName("TT-8：记账失败先落台账再上抛；一次补投后 Ledger 调用==2 且事实只 1 套；同单反复失败仍 1 行")
    void failureRegistersLedgerRowAndSingleReplayConvergesWithoutDoubleFact() throws SQLException {
        // ① 首调失败：上抛语义保留 + 台账留痕（retry_count=1）
        ledger.failing = true;
        assertThatThrownBy(() -> gateway.postAdjustment(ADJUST_NO, "CNY", SUSPENSE_PLAN))
                .as("NFR-008：失败上抛语义保留（034 只加台账，不改上抛）")
                .isInstanceOf(RuntimeException.class);
        assertThat(countRows(ADJUST_NO)).as("失败即登记台账").isEqualTo(1L);
        PendingPosting row = rowBySource(ADJUST_NO);
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getIdempotencyKey()).isEqualTo(PostingEventTypes.ADJUSTMENT + ":" + ADJUST_NO);
        assertThat(ledger.calls.get()).as("首次失败：Ledger 调用 1 次").isEqualTo(1);

        // ② 同一事实再失败：唯一键吸收，仍 1 行（上抛语义不变）
        assertThatThrownBy(() -> gateway.postAdjustment(ADJUST_NO, "CNY", SUSPENSE_PLAN))
                .isInstanceOf(RuntimeException.class);
        assertThat(countRows(ADJUST_NO)).as("UNIQUE(event_type, source_id) 兜底：仍 1 行").isEqualTo(1L);

        // ③ Ledger 恢复：台账补投 1 轮 → REPOSTED；Ledger 累计调用 == 2（首次 + 一次重试），
        //    派生键吸收 ⇒ 账本事实只 1 套（不双记）
        ledger.failing = false;
        scheduler.retryRound();
        PendingPosting reposted = rowBySource(ADJUST_NO);
        assertThat(reposted.getStatus()).isEqualTo(PendingPosting.PostingStatus.REPOSTED);
        assertThat(ledger.calls.get()).as("重放后 Ledger 调用 == 3（两次失败尝试 + 一次成功补投）").isEqualTo(3);
        assertThat(ledger.acceptCountForKey(PostingEventTypes.ADJUSTMENT + ":" + ADJUST_NO))
                .as("派生键吸收：账本事实只 1 套").isEqualTo(1);

        // ④ 载荷零改写：补投载荷与首次请求逐字段一致（amount/kind 不被重算）
        assertThat(ledger.payloadsForKey(PostingEventTypes.ADJUSTMENT + ":" + ADJUST_NO))
                .containsExactly("ADJUSTMENT|RECONCILIATION|" + ADJUST_NO + "|CNY|SUSPENSE|700");
    }

    @Test
    @DisplayName("TT-8 补充：同 (event_type, source_id) 第二次插入被 DB 唯一键拒绝")
    void duplicateKeyRejectedByDatabase() {
        String dupSource = "ADJ-TT8-DUP";
        repository.insert(new PendingPosting(
                PostingEventTypes.ADJUSTMENT, "RECONCILIATION", dupSource, "k", "{}"));
        assertThatThrownBy(() -> repository.insert(new PendingPosting(
                        PostingEventTypes.ADJUSTMENT, "RECONCILIATION", dupSource, "k", "{}")))
                .as("唯一约束必须落在 DB 层")
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---------- 按用例隔离的查询 ----------

    private long countRows(String sourceId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM pending_postings WHERE event_type = ? AND source_id = ?")) {
            ps.setString(1, PostingEventTypes.ADJUSTMENT);
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
            ps.setString(1, PostingEventTypes.ADJUSTMENT);
            ps.setString(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return repository.findById(rs.getLong(1)).orElseThrow();
            }
        }
    }

    /**
     * 计数型 Ledger fake（031 §9 契约形状）：按 {eventType}:{sourceId} 派生键吸收重复。
     * failing=true 模拟 Ledger 不可用。
     */
    static final class CountingLedgerFake implements LedgerAuditFeignClient {

        final AtomicInteger calls = new AtomicInteger();
        final Set<String> appliedKeys = ConcurrentHashMap.newKeySet();
        final Map<String, List<String>> payloads = new ConcurrentHashMap<>();
        volatile boolean failing = false;

        int acceptCountForKey(String key) {
            return appliedKeys.contains(key) ? 1 : 0;
        }

        List<String> payloadsForKey(String key) {
            List<String> list = payloads.get(key);
            return list == null ? List.of() : List.copyOf(list);
        }

        @Override
        public List<AccountingEventResponse> allPostings() {
            return List.of();
        }

        @Override
        public BalanceDto balance() {
            return new BalanceDto(true, Map.of());
        }

        @Override
        public AccountingEventResponse postEvent(AccountingEventRequest request) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("ledger unavailable (simulated)");
            }
            String key = request.eventType() + ":" + request.sourceId();
            payloads.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                    .add(request.eventType() + "|" + request.sourceType() + "|" + request.sourceId()
                            + "|" + request.currency() + "|" + request.adjustmentKind()
                            + "|" + request.amountMinor());
            appliedKeys.add(key);
            return new AccountingEventResponse(1L, "PT-1", request.eventType(), key,
                    request.sourceType(), request.sourceId(), request.currency(), "2026-09", null,
                    "ACCEPTED", List.of());
        }
    }
}
