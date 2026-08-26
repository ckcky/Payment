package com.payment.common.core.money;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 金额不变量（T017）：金额以最小货币单位存储、币种一致、溢出拒绝、禁止浮点。
 */
class MoneyInvariantTest {

    @Test
    void constructsFromMinorUnits() {
        Money m = Money.ofMinor(1250, "USD");
        assertThat(m.getAmountMinor()).isEqualTo(1250L);
        assertThat(m.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void zeroIsZero() {
        Money zero = Money.zero("CNY");
        assertThat(zero.isZero()).isTrue();
        assertThat(zero.getAmountMinor()).isZero();
        assertThat(zero.getCurrencyCode()).isEqualTo("CNY");
    }

    @Test
    void addSubtractNegate() {
        Money a = Money.ofMinor(1000, "USD");
        Money b = Money.ofMinor(250, "USD");
        assertThat(a.add(b)).isEqualTo(Money.ofMinor(1250, "USD"));
        assertThat(a.subtract(b)).isEqualTo(Money.ofMinor(750, "USD"));
        assertThat(a.negate()).isEqualTo(Money.ofMinor(-1000, "USD"));
    }

    @Test
    void overflowRejected() {
        Money max = Money.ofMinor(Long.MAX_VALUE, "USD");
        Money one = Money.ofMinor(1, "USD");
        assertThatThrownBy(() -> max.add(one)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void crossCurrencyRejected() {
        Money usd = Money.ofMinor(100, "USD");
        Money cny = Money.ofMinor(100, "CNY");
        assertThatThrownBy(() -> usd.add(cny)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usd.compareTo(cny)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactDecimalConstructs() {
        Money m = Money.of(new BigDecimal("1.25"), "USD", 2);
        assertThat(m.getAmountMinor()).isEqualTo(125L);
    }

    @Test
    void impreciseDecimalRejected() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"), "USD", 2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void noFloatingPointSurface() {
        for (Constructor<?> c : Money.class.getDeclaredConstructors()) {
            for (Class<?> t : c.getParameterTypes()) {
                assertThat(t).isNotEqualTo(float.class).isNotEqualTo(double.class)
                        .isNotEqualTo(Float.class).isNotEqualTo(Double.class);
            }
        }
        for (Method m : Money.class.getDeclaredMethods()) {
            for (Class<?> t : m.getParameterTypes()) {
                assertThat(t).isNotEqualTo(float.class).isNotEqualTo(double.class)
                        .isNotEqualTo(Float.class).isNotEqualTo(Double.class);
            }
        }
    }
}
