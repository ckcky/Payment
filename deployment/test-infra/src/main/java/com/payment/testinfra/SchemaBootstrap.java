package com.payment.testinfra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Schema 装配器（spec 033 §5.2 O-2 / C-1）：测试所用 DDL <b>只能</b>来自
 * {@code deployment/schema/NN-*.sql}——「测试 DDL == 生产 DDL」的单一事实来源，
 * 禁止任何测试专用副本（消除全仓「每服务一份 schema.sql」的双份漂移病灶）。
 *
 * <p>装配语义：剥掉脚本自带的 {@code CREATE DATABASE} / {@code USE}（库由基座按类分配，C-2），
 * 以 {@code allowMultiQueries} 在目标库整体执行；执行前可断言脚本必含指定约束名
 * （防假绿②：约束被改名/删除时测试立刻红，而不是静默证不了）。</p>
 */
public final class SchemaBootstrap {

    private SchemaBootstrap() {
    }

    /** 一份待装配脚本：{@code name} 是 deployment/schema/ 下的文件名；{@code requiredMarkers} 为必含约束断言。 */
    public record Script(String name, String... requiredMarkers) {
    }

    /**
     * 在指定 database 上装配脚本集（每脚本一个事务外多语句执行，与 030 手搓先例等价）。
     * {@code requiredMarkers} 断言失败（含约束名缺失）⇒ 抛 AssertionError。
     */
    public static void apply(String database, Collection<Script> scripts) {
        for (Script script : scripts) {
            String ddl = readScript(script.name());
            for (String marker : script.requiredMarkers()) {
                if (!ddl.contains(marker)) {
                    throw new AssertionError(
                            "真实 schema 必须保留约束/标记 \"" + marker + "\"（" + script.name() + "）；"
                                    + "约束被改名或删除时测试必须立刻失败，避免测试与生产 schema 各说各话（禁假绿②）");
                }
            }
            execute(database, stripOwnershipStatements(ddl));
        }
    }

    /** 便捷重载：无必含断言的装配。 */
    public static void apply(String database, String... scriptNames) {
        List<Script> scripts = new ArrayList<>();
        for (String name : scriptNames) {
            scripts.add(new Script(name));
        }
        apply(database, scripts);
    }

    /**
     * 读取脚本原文（路径白名单：只能解析到 {@code deployment/schema/} 目录内，
     * 传入任何形式的目录逃逸路径即抛异常——C-1 的机器化口径）。
     */
    public static String readScript(String scriptName) {
        Path schemaDir = resolveSchemaDir();
        Path target = schemaDir.resolve(scriptName).normalize();
        if (!target.startsWith(schemaDir) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException(
                    "脚本 " + scriptName + " 不在 " + schemaDir + " 内或不存在——"
                            + "C-1：测试 DDL 只能来自 deployment/schema/，禁止测试专用副本或任意路径");
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 schema 脚本失败：" + target, e);
        }
    }

    /**
     * 剥掉库归属语句（{@code CREATE DATABASE ...} / {@code USE ...}，行首大小写不敏感）：
     * 库由基座按「类内独占 database」分配（C-2），脚本自带的归属必须让位。
     * 包内可见以便对剥离语义做纯函数单测。
     */
    static String stripOwnershipStatements(String ddl) {
        return ddl.lines()
                .filter(line -> {
                    String upper = line.trim().toUpperCase(Locale.ROOT);
                    return !upper.startsWith("CREATE DATABASE") && !upper.startsWith("USE ");
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 定位 deployment/schema/（零假设：兼容「模块目录 / 仓库根 / 服务目录」三种 Surefire 工作目录）。
     * 解析一次后缓存——目录位置在一次 JVM 生命周期内不变。
     */
    private static Path resolveSchemaDir() {
        Path[] candidates = {
                Paths.get("..", "schema"),                 // cwd = deployment/test-infra（本模块自测）
                Paths.get("..", "..", "deployment", "schema"), // cwd = deployment/<两级的模块>
                Paths.get("..", "deployment", "schema"),   // cwd = <service>-service（消费方服务）
                Paths.get("deployment", "schema")          // cwd = 仓库根
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "找不到 deployment/schema/ 目录（候选：..\\schema、..\\..\\deployment\\schema、"
                        + "..\\deployment\\schema、deployment\\schema）——C-1 要求 DDL 单一来源，无兜底副本");
    }

    private static void execute(String database, String ddl) {
        try (Connection connection = DriverManagerHolder.connect(database, true);
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("schema 装配失败（database=" + database + "）", e);
        }
    }

    /** 驱动加载与连接（独立小类避免 SchemaBootstrap 构造歧义）。 */
    private static final class DriverManagerHolder {
        static Connection connect(String database, boolean multiStatements) throws SQLException {
            return java.sql.DriverManager.getConnection(
                    RealMysqlContainer.jdbcUrl(database, multiStatements),
                    RealMysqlContainer.username(),
                    RealMysqlContainer.password());
        }
    }
}
