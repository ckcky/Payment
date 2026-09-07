package com.payment.e2e.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 失败诊断落盘（spec 022 / T409，FR-007）：
 * 用例失败时把响应体 / trace 快照 / 相关表 SELECT * / invariants.log 写入
 * {@code target/e2e-dump/<case>/}，供 CI 产物归档与失败定位。
 */
public final class Dump {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path ROOT = Path.of("target", "e2e-dump");

    /** 创建（或复用）一个用例的 dump 目录；记录用例上下文。 */
    public static Context forCase(String caseName) {
        Path dir = ROOT.resolve(caseName + "-" + Long.toString(System.currentTimeMillis(), 36));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create dump dir " + dir, e);
        }
        return new Context(dir);
    }

    /** 单用例 dump 上下文：所有产物带 step 前缀写入同一目录。 */
    public static final class Context {
        private final Path dir;
        private final List<String> invariantLog = new ArrayList<>();

        Context(Path dir) {
            this.dir = dir;
        }

        /** 记录一次 API 响应。 */
        public void response(String step, Api.ApiResponse resp) {
            write(step + ".response.json", "HTTP " + resp.status() + "\n" + resp.body());
        }

        /** 记录任意 JSON 对象（trace 快照 / 表快照等）。 */
        public void json(String step, Object value) {
            try {
                write(step + ".json", MAPPER.writeValueAsString(value));
            } catch (IOException e) {
                write(step + ".json", String.valueOf(value));
            }
        }

        /** 追加一行不变量评估记录。 */
        public void invariant(String line) {
            invariantLog.add(line);
        }

        /** 用例结束（无论成败）落 invariant 日志。 */
        public void flush() {
            if (!invariantLog.isEmpty()) {
                write("invariants.log", String.join("\n", invariantLog));
            }
        }

        public Path dir() {
            return dir;
        }

        private void write(String name, String content) {
            try {
                Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[dump] write failed " + name + ": " + e.getMessage());
            }
        }
    }

    /** 便捷：把某 schema 某表按过滤条件的全表 SELECT * 落盘。 */
    public static void tableSnapshot(Context ctx, Db db, String step, String schema, String table, String whereClause) {
        String sql = "SELECT * FROM " + table + (whereClause == null || whereClause.isBlank() ? "" : " WHERE " + whereClause);
        List<Map<String, Object>> rows = db.query(schema, sql);
        ctx.json(step + "-" + schema + "-" + table, rows);
    }

    private Dump() {
    }
}
