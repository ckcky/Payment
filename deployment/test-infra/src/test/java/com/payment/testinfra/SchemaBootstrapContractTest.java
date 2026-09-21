package com.payment.testinfra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SchemaBootstrap 的纯函数契约单测（无容器）：C-1 路径白名单 + 归属语句剥离。
 * 真库装配路径见 {@link SchemaBootstrapRealDbTest}。
 */
class SchemaBootstrapContractTest {

    @Test
    @DisplayName("C-1：脚本只能解析到 deployment/schema/ 内——目录逃逸路径一律拒绝")
    void rejectsScriptsOutsideDeploymentSchema() {
        assertThatThrownBy(() -> SchemaBootstrap.readScript("../pom.xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deployment/schema");
        assertThatThrownBy(() -> SchemaBootstrap.readScript("../../pom.xml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaBootstrap.readScript("no-such-script.sql"))
                .as("不存在的脚本同样拒绝（fail fast，不允许静默空装配）")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("C-1 正例：真实生产脚本可读且非空（证明测试 DDL 与生产 DDL 同源）")
    void readsRealDeploymentSchema() {
        String ddl = SchemaBootstrap.readScript("10-audit-schema.sql");
        assertThat(ddl).isNotBlank().contains("CREATE TABLE");
    }

    @Test
    @DisplayName("C-2：剥离 CREATE DATABASE / USE（大小写不敏感、仅行首）")
    void stripsOwnershipStatements() {
        String stripped = SchemaBootstrap.stripOwnershipStatements("""
                -- 注释里提到 USE 不是语句
                CREATE DATABASE IF NOT EXISTS `probe` DEFAULT CHARACTER SET utf8mb4;
                USE `probe`;
                create database if not exists `probe2`;
                use  `probe2`;
                CREATE TABLE probe_t (id BIGINT PRIMARY KEY);
                INSERT INTO logs VALUES ('USE x 伪装前缀的数据行');
                """);
        assertThat(stripped)
                .doesNotContain("CREATE DATABASE")
                .doesNotContain("USE `probe`")
                .contains("CREATE TABLE probe_t")
                .contains("-- 注释里提到 USE 不是语句")
                .contains("INSERT INTO logs VALUES");
    }
}
