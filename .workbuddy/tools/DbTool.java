// 本地开发用 JDBC 小工具：执行 SQL 脚本 / 执行查询。
// 用法: java -cp <mysql-connector-j.jar> DbTool.java <jdbcUrl> <user> <password> <mode> <arg>
//   mode=script  arg=SQL 文件路径（支持 CREATE DATABASE / USE，按 ; 切分，忽略 -- 注释行）
//   mode=query   arg=SQL 文本（结果以 | 分隔打印）
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

public class DbTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("usage: DbTool <url> <user> <pass> <script|query> <arg>");
            System.exit(2);
        }
        String url = args[0], user = args[1], pass = args[2], mode = args[3], arg = args[4];
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            if ("script".equals(mode)) {
                runScript(conn, Files.readString(Path.of(arg)));
            } else {
                runQuery(conn, arg);
            }
        }
    }

    static void runScript(Connection conn, String sql) throws Exception {
        for (String raw : splitStatements(sql)) {
            String stmt = raw.trim();
            if (stmt.isEmpty()) continue;
            try (Statement st = conn.createStatement()) {
                st.execute(stmt);
                System.out.println("OK  " + oneLine(stmt));
            } catch (Exception e) {
                System.out.println("ERR " + oneLine(stmt) + "  -> " + e.getMessage());
            }
        }
    }

    static void runQuery(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if (i > 1) header.append(" | ");
                header.append(md.getColumnLabel(i));
            }
            System.out.println(header);
            int rows = 0;
            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) line.append(" | ");
                    line.append(rs.getString(i));
                }
                System.out.println(line);
                rows++;
            }
            System.out.println("(" + rows + " rows)");
        }
    }

    /** 按分号切分，跳过多行注释与 -- 行注释（简单实现，够用于本项目 DDL）。 */
    static java.util.List<String> splitStatements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') { inLineComment = false; cleaned.append(c); }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && n == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (c == '-' && n == '-') { inLineComment = true; i++; continue; }
            if (c == '/' && n == '*') { inBlockComment = true; i++; continue; }
            cleaned.append(c);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            out.add(part);
        }
        return out;
    }

    static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 100 ? t.substring(0, 100) + "..." : t;
    }
}
