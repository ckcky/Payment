package com.payment.testinfra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * 降级契约单测（纯 JVM，无 Docker 依赖）：skip-vs-fail 的决策面必须可被直接证伪，
 * 而不是「CI 上碰巧有 Docker 所以永远走不到 skip 分支」。
 */
class DockerContractTest {

    @AfterEach
    void restore() {
        System.clearProperty(DockerContract.REQUIRED_PROPERTY);
        DockerContract.setProbeForTesting(() -> {
            try {
                return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable unavailable) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("Docker 可用 ⇒ 一律放行（无论 required 与否）")
    void availableAlwaysPasses() {
        assertThatCode(() -> DockerContract.decide(true, false)).doesNotThrowAnyException();
        assertThatCode(() -> DockerContract.decide(true, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("无 Docker 且未要求强制 ⇒ 类级 skip（TestAbortedException），本地不假红 [禁假红]")
    void unavailableWithoutRequiredAbortsAsSkip() {
        assertThatThrownBy(() -> DockerContract.decide(false, false))
                .as("本地无 Docker 必须走 skip 而非 fail（禁假红：规范不得被环境卡死）")
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    @DisplayName("无 Docker 但 -Drealdb.required=true ⇒ fail（skip 即红）[禁假绿①]")
    void unavailableWithRequiredFails() {
        assertThatThrownBy(() -> DockerContract.decide(false, true))
                .as("CI 上真库测试被跳过 = 红：skip 契约在 required 模式下必须翻转为 fail")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("realdb.required");
    }

    @Test
    @DisplayName("ensureAvailableOrSkip 读系统属性裁决（替换探测器后端到端可验）")
    void ensureHonorsSystemProperty() {
        DockerContract.setProbeForTesting(() -> false);
        System.setProperty(DockerContract.REQUIRED_PROPERTY, "true");
        assertThatThrownBy(() -> DockerContract.ensureAvailableOrSkip(getClass()))
                .isInstanceOf(IllegalStateException.class);
        System.clearProperty(DockerContract.REQUIRED_PROPERTY);
        assertThatThrownBy(() -> DockerContract.ensureAvailableOrSkip(getClass()))
                .isInstanceOf(TestAbortedException.class);
        assertThat(Boolean.getBoolean(DockerContract.REQUIRED_PROPERTY))
                .as("required 属性恢复默认 false，避免污染同 JVM 的其他真库测试").isFalse();
    }
}
