package com.payment.testinfra;

import java.time.Duration;
import java.util.Locale;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 进程级单例 MySQL 容器（spec 033 §5.2 C-2：容器跨类复用）。
 *
 * <p>每类起一个 MySQL 约 25~40s——基座的价值就是把这笔开销从「每类一次」降到「每 JVM 一次」。
 * 隔离性不靠多容器，靠<b>类内独占 database</b>（见 {@link #databaseNameFor(Class)}）。
 * 容器不显式 stop：由 Testcontainers（Ryuk）在 JVM 退出时统一回收。</p>
 *
 * <p>启动参数与生产 compose 对齐（C-3：方言差异是假绿主因）：
 * {@code utf8mb4 / utf8mb4_unicode_ci / 默认时区 UTC}；连接串统一 {@code serverTimezone=UTC}。</p>
 *
 * <p>C-6（不假设宿主 3306 空闲）：端口完全由 Testcontainers 随机映射，与
 * {@code start-all.sh} / demo / L4 E2E 的外部 MySQL 天然互斥不冲突。</p>
 */
final class RealMysqlContainer {

    private static final Object LOCK = new Object();
    private static volatile MySQLContainer<?> shared;

    private RealMysqlContainer() {
    }

    /** 取共享容器；首个调用者负责启动（双检锁），后续复用。 */
    static MySQLContainer<?> get() {
        MySQLContainer<?> current = shared;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (shared == null) {
                MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                        .withUsername("root")
                        .withPassword("realdb-root")
                        // C-3：与生产 compose 对齐的字符集/排序规则/时区（方言一致性 = 假绿防线）
                        .withCommand("--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_unicode_ci",
                                "--default-time-zone=+00:00")
                        .withStartupTimeout(Duration.ofMinutes(3));
                container.start();
                shared = container;
            }
            return shared;
        }
    }

    /**
     * 类内独占 database 名（C-2）：{@code t_<类名小写>_<FQCN 哈希>}——
     * 同简单名不同包、哈希裁剪截断都不会撞库；确定性（同类必同名，重跑可重入）。
     */
    static String databaseNameFor(Class<?> testClass) {
        String simple = testClass.getSimpleName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (simple.isEmpty()) {
            simple = "test";
        }
        String hash = Integer.toHexString(testClass.getName().hashCode());
        String name = "t_" + simple + "_" + hash;
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    /** 指定 database 的 JDBC URL（multi: 是否允许一次 execute 多语句——SchemaBootstrap 需要）。 */
    static String jdbcUrl(String database, boolean multiStatements) {
        MySQLContainer<?> container = get();
        return "jdbc:mysql://" + container.getHost() + ":" + container.getMappedPort(3306)
                + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + (multiStatements ? "&allowMultiQueries=true" : "");
    }

    static String username() {
        return get().getUsername();
    }

    static String password() {
        return get().getPassword();
    }
}
