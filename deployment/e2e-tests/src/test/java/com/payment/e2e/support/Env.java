package com.payment.e2e.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * E2E 环境配置（spec 022 / T404）：
 * 从 classpath 读取 {@code e2e-<env>.properties}（env 由系统属性 {@code e2e.env} 决定，
 * 默认 {@code local}——复用本地已起栈；{@code ci} 供 nightly 使用）。
 *
 * <p>提供两类连接信息：服务 HTTP 基址（10 个服务端口）与各 schema 的 JDBC URL
 * （同实例多 schema：order/payment/fulfillment/entitlement/ledger/settlement/reconciliation/
 * catalog/merchant，root 账号仅限测试环境）。</p>
 */
public final class Env {

    private static final Properties props = load();

    /** 当前环境名：local（默认）| ci。 */
    public static String env() {
        return System.getProperty("e2e.env", "local");
    }

    /** 服务 HTTP 基址，如 {@code serviceUrl("order") -> http://localhost:8083}。 */
    public static String serviceUrl(String service) {
        String v = props.getProperty("service." + service);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("e2e-" + env() + ".properties missing: service." + service);
        }
        return stripTrailingSlash(v);
    }

    /** schema 的 JDBC URL。 */
    public static String jdbcUrl(String schema) {
        String v = props.getProperty("db." + schema);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("e2e-" + env() + ".properties missing: db." + schema);
        }
        return v;
    }

    public static String dbUser() {
        return props.getProperty("db.user", "root");
    }

    public static String dbPassword() {
        return props.getProperty("db.password", "root");
    }

    /** Awaitility 轮询超时（毫秒），默认 15s（NFR-004），可经 e2e.await.timeout-ms 覆盖。 */
    public static long awaitTimeoutMs() {
        return Long.parseLong(props.getProperty("e2e.await.timeout-ms", "15000"));
    }

    /** 本轮唯一业务号前缀（FR-013 数据隔离）：e2e-{runId}。 */
    public static String runPrefix() {
        return "e2e-" + Long.toString(System.currentTimeMillis(), 36);
    }

    /**
     * 当前环境里「浏览器/客户端视角」与「测试进程视角」是否同一网络。
     *
     * <p>用于判定 {@code payUrl} 可达性用例（CashierPayUrlReachabilityTest）的口径：</p>
     * <ul>
     *   <li>{@code local}（默认 true）：测试跑在宿主、服务在容器/宿主，**客户端视角 = 宿主**，
     *       故 payUrl 必须是宿主可达地址（不得含容器内服务名）——这正是 2026-09-15 缺陷的判据；</li>
     *   <li>{@code ci}（默认 false）：测试 JVM 与各服务同处一个容器网络，服务名可解析，
     *       payUrl 用服务名是合理的，此时跳过「不得含服务名」的断言，只保留直连可达性。</li>
     * </ul>
     */
    public static boolean clientAndTestShareNetwork() {
        return Boolean.parseBoolean(
                props.getProperty("e2e.client-shared-network", "false"));
    }

    /**
     * 客户端视角可达性断言是否可用（默认 true）。
     *
     * <p>当测试进程本身无法触达客户端网络（例如某些 CI 拓扑）时可置 false 跳过该断言，
     * 避免产生「环境导致的假红」。本地与默认 CI 拓扑均应保持 true——这正是本用例的价值所在。</p>
     */
    public static boolean clientReachabilityCheckEnabled() {
        return Boolean.parseBoolean(
                props.getProperty("e2e.client-reachability-check", "true"));
    }

    private static Properties load() {
        String env = System.getProperty("e2e.env", "local");
        String resource = "e2e-" + env + ".properties";
        try (InputStream in = Env.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + resource);
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + resource, e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Env() {
    }
}
