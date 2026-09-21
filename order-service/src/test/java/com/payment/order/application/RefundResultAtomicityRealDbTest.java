package com.payment.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.common.mybatis.AuditMetaObjectHandler;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.TransactionRefundRepository;
import com.payment.order.domain.TransactionRepository;
import com.payment.order.infra.persistence.order.MybatisOrderRepository;
import com.payment.order.infra.persistence.order.OrderMapper;
import com.payment.order.infra.persistence.transaction.MybatisTransactionRefundRepository;
import com.payment.order.infra.persistence.transaction.MybatisTransactionRepository;
import com.payment.order.infra.persistence.transaction.TransactionMapper;
import com.payment.order.infra.persistence.transaction.TransactionRefundMapper;
import com.payment.order.mq.OrderEventPublisher;
import com.payment.testinfra.FaultHooks;
import com.payment.testinfra.RealMysqlTestSupport;
import com.payment.testinfra.SchemaBootstrap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TT-1（spec 034 §11，L2b 真库）：C-19 修复——退款收口的本地状态迁移**同事务**原子性。
 *
 * <h3>为什么必须是真库</h3>
 * C-19 的分叉窗口是「TXRF 终态已提交 + order 未记账」：只有真库能证明 InnoDB 的
 * 事务回滚在连接被杀（{@link FaultHooks#killConnection}）后真的把三条写一起收掉。
 * in-memory 桩证不了「回滚」这件事——它们本来就没有提交语义。
 *
 * <h3>注入点</h3>
 * 测试自带一个 MyBatis {@link Interceptor}，在订单表 UPDATE（order save，事务内第三条写）
 * 执行前杀掉事务连接——精确落进「TXRF save 之后、order save 之前」的崩溃窗口。
 * 断言两段：① 崩溃后三张表**全部**保持迁移前状态（旧实现此处 TXRF 已终态 ⇒ 分叉）；
 * ② 重放同一通知后三方一致收敛（TXRF 终态 / transaction.refunded_minor / order.refunded_minor）。
 *
 * <h3>装配方式</h3>
 * 非 Spring 容器测试：手工装配 MyBatis-Plus（对齐 {@code MybatisCommonAutoConfiguration}
 * 的拦截器 + 审计填充）+ {@link DataSourceTransactionManager}，与生产运行时语义一致。
 * 无 Docker 时按基座契约整体 skip（CI real-db job 上 skip 即红）。
 */
class RefundResultAtomicityRealDbTest extends RealMysqlTestSupport {

    private static final String ORDER_NO = "OR-TT1";
    private static final String TX_NO = "TX-TT1";
    private static final String TXRF_NO = "TXRF-TT1";
    private static final String PMRF_NO = "PMRF-TT1";
    private static final String PAYMENT_NO = "PM-TT1";
    private static final long ORDER_AMOUNT = 10_000L;
    private static final long REFUND_AMOUNT = 3_000L;

    private final KillOnOrderUpdateInterceptor killInterceptor = new KillOnOrderUpdateInterceptor();
    private TransactionApplicationService service;
    private TransactionRefundRepository refundRepository;
    private RecordingOrderEventPublisher mqPublisher;

    @Override
    protected List<SchemaBootstrap.Script> schemaScripts() {
        // 01-order-schema.sql 的 TXRF 幂等唯一约束一旦被改名/删除，本类立刻红（禁假绿②）。
        return List.of(new SchemaBootstrap.Script("01-order-schema.sql", "uk_transaction_refunds_refund_no"));
    }

    @BeforeAll
    void wireService() throws Exception {
        DataSource dataSource = new OpenConnectionDataSource();
        killInterceptor.bind(dataSource);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // 对齐生产 MybatisCommonAutoConfiguration：乐观锁 + 分页 + 审计填充
        MybatisPlusInterceptor mpInterceptor = new MybatisPlusInterceptor();
        mpInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        mpInterceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factoryBean.setPlugins(mpInterceptor, killInterceptor);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new AuditMetaObjectHandler());
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        // 无 mapperLocations（全注解式 BaseMapper）：显式登记本测试触达的 4 个 mapper
        var configuration = (com.baomidou.mybatisplus.core.MybatisConfiguration) sqlSessionFactory.getConfiguration();
        configuration.addMapper(com.payment.order.infra.persistence.order.OrderItemMapper.class);
        configuration.addMapper(OrderMapper.class);
        configuration.addMapper(TransactionMapper.class);
        configuration.addMapper(TransactionRefundMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);

        refundRepository = new MybatisTransactionRefundRepository(template.getMapper(TransactionRefundMapper.class));
        TransactionRepository transactionRepository =
                new MybatisTransactionRepository(template.getMapper(TransactionMapper.class));
        OrderRepository orderRepository =
                new MybatisOrderRepository(template.getMapper(OrderMapper.class),
                        template.getMapper(com.payment.order.infra.persistence.order.OrderItemMapper.class));

        mqPublisher = new RecordingOrderEventPublisher();
        BusinessMetrics metrics = new NoopBusinessMetrics();
        service = new TransactionApplicationService(orderRepository, transactionRepository, refundRepository,
                null /* orderLayer：onRefundResult 不经 order 层 */,
                null /* paymentGateway：收口路径不发退款命令 */,
                null /* fulfillmentGateway：MQ 路径下不触达 */,
                null /* catalogClient：MQ 路径下不触达 */,
                metrics, new StructuredAuditLogger(),
                new SingletonObjectProvider(mqPublisher),
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("TT-1：TXRF 终态迁移后、order 记账前连接被杀 ⇒ 三写整体回滚，重放一致收敛 [C-19]")
    void refundResultMigrationIsAtomicUnderConnectionKill() throws Exception {
        seedPaidOrderWithProcessingRefund();

        RefundResultNotification notification = new RefundResultNotification(
                TXRF_NO, PMRF_NO, TX_NO, ORDER_NO, PAYMENT_NO, REFUND_AMOUNT, "CNY", "SUCCEEDED", null);

        // ① 崩溃注入：order save（事务内第三条写）前杀连接 ⇒ 本次收口整体回滚
        killInterceptor.armed.set(true);
        assertThatThrownBy(() -> service.onRefundResult(notification))
                .as("连接被杀必须让收口失败上抛（payment 侧重推通知重放）")
                .isInstanceOf(RuntimeException.class);

        // 原子性核心断言：崩溃后 **三张表全部** 保持迁移前状态。
        // （修复前：TXRF 先行独立提交 ⇒ 此处已 SUCCEEDED 而 order/refunded 为 0 ⇒ 永久分叉。）
        assertThat(statusOf("transaction_refunds", "refund_no", TXRF_NO))
                .as("TXRF 终态迁移必须随事务回滚").isEqualTo("PROCESSING");
        assertThat(longOf("orders", "refunded_minor", "order_no", ORDER_NO))
                .as("订单退款累计必须随事务回滚").isZero();
        assertThat(longOf("transactions", "refunded_minor", "transaction_no", TX_NO))
                .as("交易退款累计必须随事务回滚").isZero();
        assertThat(mqPublisher.refundPublished.get()).as("事务未提交不得扇出 MQ").isZero();

        // ② 重放（payment 侧重推）：三方一致收敛
        service.onRefundResult(notification);

        assertThat(statusOf("transaction_refunds", "refund_no", TXRF_NO)).isEqualTo("SUCCEEDED");
        assertThat(longOf("transactions", "refunded_minor", "transaction_no", TX_NO)).isEqualTo(REFUND_AMOUNT);
        assertThat(longOf("orders", "refunded_minor", "order_no", ORDER_NO)).isEqualTo(REFUND_AMOUNT);
        assertThat(statusOf("orders", "order_no", ORDER_NO)).as("部分退款推进 PARTIALLY_REFUNDED")
                .isEqualTo("PARTIALLY_REFUNDED");
        assertThat(mqPublisher.refundPublished.get()).as("提交后扇出恰好一次").isEqualTo(1);

        // ③ 再重放：终态吸收，幂等不重复记账、不重复扇出
        service.onRefundResult(notification);
        assertThat(longOf("transactions", "refunded_minor", "transaction_no", TX_NO)).isEqualTo(REFUND_AMOUNT);
        assertThat(longOf("orders", "refunded_minor", "order_no", ORDER_NO)).isEqualTo(REFUND_AMOUNT);
        assertThat(mqPublisher.refundPublished.get()).as("replay absorbed 不得二次扇出").isEqualTo(1);
    }

    // ---------- 造数与查询 ----------

    private void seedPaidOrderWithProcessingRefund() throws SQLException {
        try (Connection c = openConnection()) {
            exec(c, "INSERT INTO orders (order_no, user_id, merchant_id, payment_no, status, currency_code, "
                    + "total_minor, paid_minor, refunded_minor, created_at, updated_at, version) "
                    + "VALUES ('" + ORDER_NO + "', 'u1', 'm1', '" + PAYMENT_NO + "', 'PAID', 'CNY', "
                    + ORDER_AMOUNT + ", " + ORDER_AMOUNT + ", 0, NOW(), NOW(), 1)");
            exec(c, "INSERT INTO order_items (order_item_no, order_no, sku_id, sku_code, name, quantity, "
                    + "price_minor, currency_code, created_at, updated_at, version) "
                    + "VALUES ('OI-TT1-1', '" + ORDER_NO + "', '1', 'SKU-A', 'Item A', 1, "
                    + ORDER_AMOUNT + ", 'CNY', NOW(), NOW(), 1)");
            exec(c, "INSERT INTO transactions (transaction_no, order_no, amount_minor, currency_code, "
                    + "purpose, status, payment_no, refunded_minor, created_at, updated_at, version) "
                    + "VALUES ('" + TX_NO + "', '" + ORDER_NO + "', " + ORDER_AMOUNT + ", 'CNY', "
                    + "'PAYMENT', 'SUCCEEDED', '" + PAYMENT_NO + "', 0, NOW(), NOW(), 1)");
            exec(c, "INSERT INTO transaction_refunds (refund_no, payment_refund_no, transaction_no, order_no, "
                    + "payment_no, user_id, amount_minor, currency_code, status, reason, failure_reason, "
                    + "idempotency_key, created_at, updated_at, version) "
                    + "VALUES ('" + TXRF_NO + "', '" + PMRF_NO + "', '" + TX_NO + "', '" + ORDER_NO + "', "
                    + "'" + PAYMENT_NO + "', 'u1', " + REFUND_AMOUNT + ", 'CNY', 'PROCESSING', 'TT1', NULL, "
                    + "'" + TXRF_NO + "', NOW(), NOW(), 1)");
        }
    }

    private String statusOf(String table, String keyColumn, String keyValue) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM `" + table + "` WHERE " + keyColumn + " = ?")) {
            ps.setString(1, keyValue);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private long longOf(String table, String valueColumn, String keyColumn, String keyValue) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + valueColumn + " FROM `" + table + "` WHERE " + keyColumn + " = ?")) {
            ps.setString(1, keyValue);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // ---------- 测试专用装配件 ----------

    /**
     * 崩溃注入拦截器：订单表 UPDATE 执行前 KILL 事务连接（精确命中
     * 「TXRF save 之后、order save 之前」的 C-19 窗口）。
     */
    @org.apache.ibatis.plugin.Intercepts({
            @org.apache.ibatis.plugin.Signature(type = Executor.class, method = "update",
                    args = {MappedStatement.class, Object.class})})
    static class KillOnOrderUpdateInterceptor implements Interceptor {

        final java.util.concurrent.atomic.AtomicBoolean armed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean spent =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private DataSource dataSource;

        void bind(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            if (armed.get() && !spent.get()
                    && ms.getId().equals("com.payment.order.infra.persistence.order.OrderMapper.updateById")) {
                spent.set(true);
                Executor executor = (Executor) invocation.getTarget();
                Connection victim = executor.getTransaction().getConnection();
                try (Connection admin = dataSource.getConnection()) {
                    FaultHooks.killConnection(victim, admin);
                }
            }
            return invocation.proceed();
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }
    }

    /** 无池 DataSource：getConnection 即新开容器连接（单 tx 由 Spring 事务绑定单一连接）。 */
    class OpenConnectionDataSource extends org.springframework.jdbc.datasource.AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return openConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return openConnection();
        }
    }

    /** 单元素 ObjectProvider 桩：getIfAvailable() 恒返回给定实例。 */
    static class SingletonObjectProvider implements org.springframework.beans.factory.ObjectProvider<OrderEventPublisher> {
        private final OrderEventPublisher instance;

        SingletonObjectProvider(OrderEventPublisher instance) {
            this.instance = instance;
        }

        @Override
        public OrderEventPublisher getIfAvailable() {
            return instance;
        }

        @Override
        public OrderEventPublisher getObject(Object... args) {
            return instance;
        }

        @Override
        public OrderEventPublisher getObject() {
            return instance;
        }
    }

    /** MQ 桩：只记录 publishRefundSucceeded 次数（MQ 路径下收口不应触达 producer）。 */
    static class RecordingOrderEventPublisher extends OrderEventPublisher {
        final java.util.concurrent.atomic.AtomicInteger refundPublished =
                new java.util.concurrent.atomic.AtomicInteger();

        RecordingOrderEventPublisher() {
            super(null, null);
        }

        @Override
        public void publishRefundSucceeded(com.payment.order.domain.RefundOrder refund, Order order) {
            refundPublished.incrementAndGet();
        }
    }
}
