package com.payment.testinfra;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;

/**
 * 真库测试基类（spec 033 §5.2 O-1 / §5.3 T1 的载体）。
 *
 * <p>子类获得三件事：</p>
 * <ol>
 *   <li><b>容器生命周期</b>——进程级单例 MySQL（跨类复用，首类付 25~40s，后续类零启动成本）；</li>
 *   <li><b>类内独占 database</b>（C-2）——{@code t_<类名>_<hash>}，跨类不共享数据，
 *       这是「唯一约束只有一个赢家」类断言的前提；</li>
 *   <li><b>无 Docker 降级契约</b>（C-4）——本地 skip + WARN 可见；CI
 *       {@code -Drealdb.required=true} 时 skip 即红（详见 {@link DockerContract}）。</li>
 * </ol>
 *
 * <p>子类通过 {@link #schemaScripts()} 声明所需的 {@code deployment/schema/} 脚本
 * （{@link SchemaBootstrap.Script} 可携带必含约束断言），通过 {@link #onDatabaseReady()}
 * 在灌库后做自有的合成表/造数——<b>基座不含任何业务词汇与业务断言</b>（C-5 / spec 033 §4）。</p>
 *
 * <p>{@code @Tag("real-db")} 随类继承：CI real-db job 用 {@code -Dgroups=real-db} 只跑真库类
 * （spec §13.1 PR 真库层）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("real-db")
public abstract class RealMysqlTestSupport {

    /** 已建库登记（幂等护栏：同一类被 @RealDb 扩展与本基类同时触达时不会重复装配）。 */
    private static final Map<String, Boolean> BOOTSTRAPPED = new ConcurrentHashMap<>();

    private String database;

    @BeforeAll
    final void bootstrapRealDatabase() {
        DockerContract.ensureAvailableOrSkip(getClass());
        database = RealMysqlRuntime.ensureClassDatabase(getClass(), schemaScripts());
        onDatabaseReady();
    }

    /** 子类声明所需 schema 脚本（默认空：基座自证测试用合成表时不需要生产 DDL）。 */
    protected Collection<SchemaBootstrap.Script> schemaScripts() {
        return List.of();
    }

    /** 灌库后的钩子：建合成表 / 造基线数据（每类自己的 database 内，跨类不可见）。 */
    protected void onDatabaseReady() {
        // 默认空实现
    }

    /** 本类独占的 database 名。 */
    protected final String databaseName() {
        return database;
    }

    /** 新开一条指向本类 database 的 JDBC 连接（单语句语义，不带 allowMultiQueries）。 */
    protected final Connection openConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                RealMysqlContainer.jdbcUrl(database, false),
                RealMysqlContainer.username(),
                RealMysqlContainer.password());
    }

    /** 建库 + 灌 schema 的共享入口（幂等：同库只装一次）。包内可见供 RealDbExtension 复用。 */
    static final class RealMysqlRuntime {
        private RealMysqlRuntime() {
        }

        static String ensureClassDatabase(Class<?> testClass, Collection<SchemaBootstrap.Script> scripts) {
            String database = RealMysqlContainer.databaseNameFor(testClass);
            BOOTSTRAPPED.computeIfAbsent(database, db -> {
                createDatabase(db);
                SchemaBootstrap.apply(db, scripts);
                return Boolean.TRUE;
            });
            return database;
        }

        private static void createDatabase(String database) {
            try (Connection connection = java.sql.DriverManager.getConnection(
                            RealMysqlContainer.jdbcUrl("mysql", false),
                            RealMysqlContainer.username(),
                            RealMysqlContainer.password());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS `" + database + "`"
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            } catch (SQLException e) {
                throw new IllegalStateException("创建独占测试库失败：" + database, e);
            }
        }
    }
}
