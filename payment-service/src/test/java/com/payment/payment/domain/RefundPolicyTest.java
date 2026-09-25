package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link RefundPolicy} 边界单测（spec 030 / B7，T17 / FR-233）。
 *
 * <p>spec §15.3 标注：该类的「超退判据用 {@code >} 而非 {@code >=}」这一结论<b>此前未经测试验证</b>。
 * 本类把边界两侧都钉死：</p>
 *
 * <ul>
 *   <li><b>恰好等额</b>（{@code alreadyRefunded + requested == paid}）⇒ <b>批准</b>
 *       ——这是「全额退款」的合法形态，若判据误写为 {@code >=} 会静默拒绝全部全额退款；</li>
 *   <li><b>超出 1 分</b>（{@code alreadyRefunded + requested == paid + 1}）⇒ <b>拒绝</b>
 *       ——超退防线，判据必须拦住。</li>
 * </ul>
 */
class RefundPolicyTest {

    private static final String CNY = "CNY";

    @Test
    void fullRefundAtExactBoundaryIsApproved() {
        // 已退 0 + 申请 10000 = 已付 10000 ⇒ 恰好等额，必须批准（判据是 >，不是 >=）
        assertThat(RefundPolicy.decide(10_000L, CNY, 10_000L, CNY, 0L).isApproved()).isTrue();
        // 已退 4000 + 申请 6000 = 已付 10000 ⇒ 累计恰好等额，同样批准
        assertThat(RefundPolicy.decide(6_000L, CNY, 10_000L, CNY, 4_000L).isApproved()).isTrue();
    }

    @Test
    void oneMinorAboveBoundaryIsRejected() {
        // 10001 > 10000 ⇒ 超退 1 分，必须拒绝
        assertThat(RefundPolicy.decide(10_001L, CNY, 10_000L, CNY, 0L).isApproved()).isFalse();
        // 累计口径：已退 4000 + 申请 6001 = 10001 > 10000 ⇒ 拒绝
        assertThat(RefundPolicy.decide(6_001L, CNY, 10_000L, CNY, 4_000L).isApproved()).isFalse();
    }

    @Test
    void nonPositiveAmountIsRejected() {
        assertThat(RefundPolicy.decide(0L, CNY, 10_000L, CNY, 0L).isApproved()).isFalse();
        assertThat(RefundPolicy.decide(-1L, CNY, 10_000L, CNY, 0L).isApproved()).isFalse();
    }

    @Test
    void currencyMismatchIsRejected() {
        // 金额合法但币种不符 ⇒ 拒绝（币种校验先于金额，与实现顺序一致）
        assertThat(RefundPolicy.decide(1_000L, "USD", 10_000L, CNY, 0L).isApproved()).isFalse();
    }

    @Test
    void refundableAmountIsNeverNegative() {
        assertThat(RefundPolicy.refundableAmount(10_000L, 0L)).isEqualTo(10_000L);
        assertThat(RefundPolicy.refundableAmount(10_000L, 4_000L)).isEqualTo(6_000L);
        assertThat(RefundPolicy.refundableAmount(10_000L, 10_000L)).isZero();
        // 异常数据（累计超过已付）不得返回负数
        assertThat(RefundPolicy.refundableAmount(10_000L, 12_000L)).isZero();
    }
}
