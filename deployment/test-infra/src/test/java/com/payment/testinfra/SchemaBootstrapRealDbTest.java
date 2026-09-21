package com.payment.testinfra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SchemaBootstrap 真库装配自证：用<b>真实生产脚本</b>（10-audit-schema.sql，全量文件之一）
 * 走完整装配路径，证明「测试所用 DDL == deployment/schema 生产 DDL」的通路成立（C-1 / O-2）。
 *
 * <p>引用脚本名只是装配机制的配置项——本类不断言任何业务语义（C-5）。</p>
 */
class SchemaBootstrapRealDbTest extends RealMysqlTestSupport {

    @Override
    protected java.util.Collection<SchemaBootstrap.Script> schemaScripts() {
        return java.util.List.of(new SchemaBootstrap.Script("10-audit-schema.sql"));
    }

    @Test
    @DisplayName("生产脚本落入类内独占库，表真实可查（information_schema 为准）")
    void appliesRealSchemaIntoOwnDatabase() throws Exception {
        String expected = databaseName();
        assertThat(expected).startsWith("t_");

        try (java.sql.Connection c = openConnection();
             PreparedStatement currentDb = c.prepareStatement("SELECT DATABASE()")) {
            try (ResultSet rs = currentDb.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1))
                        .as("装配必须发生在本类独占库（C-2 类内隔离）")
                        .isEqualTo(expected);
            }
            try (PreparedStatement exists = c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'audit_batches'")) {
                exists.setString(1, expected);
                try (ResultSet rs = exists.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1))
                            .as("生产 schema 的表必须在独占库真实存在（真 DDL 生效，非 H2 兼容层）")
                            .isEqualTo(1L);
                }
            }
        }
    }
}
