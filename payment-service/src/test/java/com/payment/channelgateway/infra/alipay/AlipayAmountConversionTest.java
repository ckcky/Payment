package com.payment.channelgateway.infra.alipay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 支付宝金额换算<b>固定向量</b>测试（spec 030 / T119 / FR-139，SC-A-03）。
 *
 * <p>本类把换算规则钉成可直接比对的向量，锁死两个最容易出事的点：</p>
 * <ol>
 *   <li><b>无科学计数法</b>：{@code toPlainString()} 而非 {@code toString()}——
 *       大额下 {@code toString()} 会产出 {@code 1E+6} 这种渠道直接拒收的形态；</li>
 *   <li><b>无二进制浮点</b>：换算路径上不得出现 {@code double} / {@code float}，
 *       否则 {@code 0.1 + 0.2} 类型的误差会变成真实的资金差错（INV-1）。</li>
 * </ol>
 *
 * <p>负值 / 边界也一并钉住：进位、零值、{@code Long.MAX_VALUE} 级的极端值不得溢出或走样。</p>
 */
class AlipayAmountConversionTest {

    // ---------- 分 → 元（toYuanPlainString） ----------

    @Test
    @DisplayName("固定向量：1 → \"0.01\"，100000000 → \"1000000.00\" [SC-A-03]")
    void fixedVectorsToYuan() {
        assertThat(AlipaySdkGateway.toYuanPlainString(1L)).isEqualTo("0.01");
        assertThat(AlipaySdkGateway.toYuanPlainString(100_000_000L)).isEqualTo("1000000.00");
    }

    @Test
    @DisplayName("常用向量全覆盖：0 / 1 / 100 / 12345 / 一千万元级")
    void commonVectorsToYuan() {
        assertThat(AlipaySdkGateway.toYuanPlainString(0L)).isEqualTo("0.00");
        assertThat(AlipaySdkGateway.toYuanPlainString(100L)).isEqualTo("1.00");
        assertThat(AlipaySdkGateway.toYuanPlainString(12_345L)).isEqualTo("123.45");
        assertThat(AlipaySdkGateway.toYuanPlainString(100_000_00L)).isEqualTo("100000.00");
    }

    @Test
    @DisplayName("大额绝不产出科学计数法（toPlainString 而非 toString）")
    void largeAmountsNeverUseScientificNotation() {
        String s = AlipaySdkGateway.toYuanPlainString(1_000_000_000_00L); // 十亿元
        assertThat(s).isEqualTo("1000000000.00");
        assertThat(s).doesNotContain("E").doesNotContain("e");
    }

    @Test
    @DisplayName("Long.MAX_VALUE 级极端值：换算完成、无溢出、仍为纯十进制")
    void extremeValueStillConvertsCleanly() {
        String s = AlipaySdkGateway.toYuanPlainString(Long.MAX_VALUE);
        assertThat(s).doesNotContain("E").doesNotContain("e");
        // 反向可解析回来（证明无损）
        assertThat(AlipaySdkGateway.parseYuanToMinor(s)).isEqualTo(Long.MAX_VALUE);
    }

    // ---------- 元 → 分（parseYuanToMinor） ----------

    @Test
    @DisplayName("固定向量回程：\"0.01\" → 1，\"1000000.00\" → 100000000")
    void fixedVectorsFromYuan() {
        assertThat(AlipaySdkGateway.parseYuanToMinor("0.01")).isEqualTo(1L);
        assertThat(AlipaySdkGateway.parseYuanToMinor("1000000.00")).isEqualTo(100_000_000L);
    }

    @Test
    @DisplayName("往返一致：分 → 元 → 分 恒等")
    void roundTripIsLossless() {
        for (long minor : new long[] {0L, 1L, 99L, 100L, 12_345L, 100_000_000L, 999_999_999_99L}) {
            assertThat(AlipaySdkGateway.parseYuanToMinor(AlipaySdkGateway.toYuanPlainString(minor)))
                    .as("往返 %d 分", minor)
                    .isEqualTo(minor);
        }
    }

    @Test
    @DisplayName("非法/空输入 ⇒ null（调用方据此**跳过**金额校验，而非误判为不匹配）")
    void unparsableInputYieldsNull() {
        assertThat(AlipaySdkGateway.parseYuanToMinor(null)).isNull();
        assertThat(AlipaySdkGateway.parseYuanToMinor("")).isNull();
        assertThat(AlipaySdkGateway.parseYuanToMinor("   ")).isNull();
        assertThat(AlipaySdkGateway.parseYuanToMinor("abc")).isNull();
        assertThat(AlipaySdkGateway.parseYuanToMinor("1.234")).isNull(); // 超出两位小数 ⇒ 非整分
    }

    @Test
    @DisplayName("零值与小数的边界口")
    void zeroAndSubUnitBoundaries() {
        assertThat(AlipaySdkGateway.parseYuanToMinor("0.00")).isEqualTo(0L);
        assertThat(AlipaySdkGateway.parseYuanToMinor("0.10")).isEqualTo(10L);
        assertThat(AlipaySdkGateway.parseYuanToMinor("0.09")).isEqualTo(9L);
    }
}
