package com.payment.common.core.dye;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link DyeMode} 解析测试（spec 030 / T114 / FR-160）。
 *
 * <p>三条硬约束：<b>缺省 = MOCK</b>（安全默认，绝不误连真实渠道）、
 * <b>大小写不敏感</b>、<b>非法值抛异常</b>（ADR-0049：配错不许静默走默认）。</p>
 */
class DyeModeTest {

    @Test
    void nullAndBlankFallBackToMock() {
        assertThat(DyeMode.parse(null)).isEqualTo(DyeMode.MOCK);
        assertThat(DyeMode.parse("")).isEqualTo(DyeMode.MOCK);
        assertThat(DyeMode.parse("   ")).isEqualTo(DyeMode.MOCK);
    }

    @Test
    void parsingIsCaseInsensitive() {
        assertThat(DyeMode.parse("mock")).isEqualTo(DyeMode.MOCK);
        assertThat(DyeMode.parse("Mock")).isEqualTo(DyeMode.MOCK);
        assertThat(DyeMode.parse("MOCK")).isEqualTo(DyeMode.MOCK);
        assertThat(DyeMode.parse("sandbox")).isEqualTo(DyeMode.SANDBOX);
        assertThat(DyeMode.parse("Sandbox")).isEqualTo(DyeMode.SANDBOX);
        assertThat(DyeMode.parse("SANDBOX")).isEqualTo(DyeMode.SANDBOX);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(DyeMode.parse("  SANDBOX  ")).isEqualTo(DyeMode.SANDBOX);
    }

    @Test
    void invalidValueThrowsInsteadOfSilentlyFallingBack() {
        // 拼错却静默走 MOCK 是最坏的结果：会让人以为自己在测沙箱
        assertThatThrownBy(() -> DyeMode.parse("SANBOX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SANBOX");
        assertThatThrownBy(() -> DyeMode.parse("PROD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DyeMode.parse("1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
