package com.payment.payment.application.channel;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.persistence.ChannelAttemptRecorderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道层写入口的契约测试（FIX-3 / FIX-4，2026-09-20 边界复审）。
 *
 * <p>覆盖两条<b>架构定义要求、此前无实现也无测试</b>的约束：</p>
 * <ol>
 *   <li><b>Payment 1:1 PaymentAttempt（FIX-3）</b>：同一支付单只允许一条 {@code PAYMENT} 尝试行。
 *       该基数关系无法用库约束表达（需要「部分索引」，而退而求其次的
 *       {@code UNIQUE(payment_no, attempt_type)} 会连合法的多条 REFUND 一起禁掉），
 *       只能落在写入口——因此必须有测试钉住，且<b>三个实现同口径</b>。</li>
 *   <li><b>退款尝试的写归位渠道层，且重复键不得无条件吸收（FIX-4）</b>：
 *       撞 {@code uk_attempts_channel_reference} 只在「同一支付单已有同引用的 REFUND 行」
 *       时才算真重放；引用被<b>非退款行</b>占用（F5 形态：把原支付交易号当退款流水号）
 *       必须抛错，否则这笔退款的渠道流水号会被静默丢掉。</li>
 * </ol>
 */
class ChannelAttemptRecorderContractTest {

    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();

    @AfterEach
    void tearDown() {
        DyeContext.clear();
    }

    // ---- FIX-3：Payment 1:1 PaymentAttempt 写侧断言 ----

    @Test
    @DisplayName("同一支付单开第二条 PAYMENT 尝试 ⇒ 明确拒绝（内存写入口口径）[FIX-3]")
    void secondPaymentAttemptIsRejectedByInMemoryRecorder() {
        attempts.openPaymentAttempt("PM-C1", "MOCK", 100L, "CNY");

        assertThatThrownBy(() -> attempts.openPaymentAttempt("PM-C1", "MOCK", 100L, "CNY"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Payment 1:1 PaymentAttempt violated")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("生产写入口 ChannelAttemptRecorderImpl 与内存桩同口径拒绝 [FIX-3]")
    void secondPaymentAttemptIsRejectedByProductionRecorder() {
        ChannelAttemptRecorderImpl recorder = new ChannelAttemptRecorderImpl(attempts);
        recorder.openPaymentAttempt("PM-C2", "MOCK", 100L, "CNY");

        assertThatThrownBy(() -> recorder.openPaymentAttempt("PM-C2", "MOCK", 100L, "CNY"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Payment 1:1 PaymentAttempt violated")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("反证：同一支付单的多条 REFUND 尝试仍合法（不变量只约束 PAYMENT）[FIX-3]")
    void multipleRefundAttemptsRemainAllowed() {
        attempts.openPaymentAttempt("PM-C3", "MOCK", 100L, "CNY");
        attempts.recordRefundAttempt("PM-C3", "MOCK", 100L, "CNY", ChannelResult.success("r-1"));
        attempts.recordRefundAttempt("PM-C3", "MOCK", 100L, "CNY", ChannelResult.success("r-2"));

        assertThat(attempts.findByPaymentNo("PM-C3")).hasSize(3);
        assertThat(attempts.findByPaymentNo("PM-C3").stream()
                .filter(a -> PaymentAttempt.TYPE_REFUND.equals(a.getAttemptType())).count())
                .as("部分退款 / 多次退款尝试是正常业务，不得被 1:1 断言误伤")
                .isEqualTo(2L);
    }

    // ---- FIX-4：退款尝试的写入归位渠道层端口 ----

    @Test
    @DisplayName("端口一步完成退款尝试的创建+收敛+落库，且记支付单金额而非退款金额 [FIX-4][D2]")
    void recordRefundAttemptCreatesConvergedRow() {
        ChannelAttemptRecorderImpl recorder = new ChannelAttemptRecorderImpl(attempts);

        PaymentAttempt recorded = recorder.recordRefundAttempt("PM-C4", "MOCK", 100L, "CNY",
                ChannelResult.success("mock-refund-ref-1-1"));

        assertThat(recorded.getAttemptType()).isEqualTo(PaymentAttempt.TYPE_REFUND);
        assertThat(recorded.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(recorded.getChannelReference()).isEqualTo("mock-refund-ref-1-1");
        assertThat(recorded.getAmountMinor())
                .as("spec 018 / D2：REFUND 行记所属支付单金额，不是退款金额")
                .isEqualTo(100L);
        assertThat(recorded.getExtra())
                .as("写入口必须盖章 channelMode（与 open*Attempt 同口径）")
                .containsKey(PaymentAttempt.CHANNEL_MODE_KEY);
    }

    @Test
    @DisplayName("真幂等重放：同支付单已有同引用的 REFUND 行 ⇒ 吸收并回放该行 [FIX-4]")
    void trueRefundReplayIsAbsorbed() {
        PaymentAttempt existing = attempts.recordRefundAttempt("PM-C5", "MOCK", 100L, "CNY",
                ChannelResult.success("mock-refund-ref-2-1"));

        PaymentAttempt absorbed = ChannelAttemptRecorder.requireTrueRefundReplay(
                attempts, "PM-C5", "mock-refund-ref-2-1");

        assertThat(absorbed).isSameAs(existing);
    }

    @Test
    @DisplayName("引用被非退款行占用（F5 形态）⇒ 抛错，绝不静默吸收 [FIX-4]")
    void referenceOwnedByNonRefundRowIsNotAbsorbed() {
        // F5 形态：退款侧把**原支付交易号**当退款流水号写进去，撞的其实是同支付单那条 PAYMENT 行
        PaymentAttempt paymentRow = attempts.openPaymentAttempt("PM-C6", "ALIPAY", 100L, "CNY");
        paymentRow.accept("2026092022001429280508654228");
        attempts.save(paymentRow);

        assertThatThrownBy(() -> ChannelAttemptRecorder.requireTrueRefundReplay(
                attempts, "PM-C6", "2026092022001429280508654228"))
                .as("无条件吸收会让「退款事实写丢了」长期隐身——必须抛错暴露引用值本身有问题")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("reference value itself is wrong")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("退款引用为空时撞键 ⇒ 同样抛错（无从判定是重放）[FIX-4]")
    void nullReferenceOnCollisionIsNotAbsorbed() {
        assertThatThrownBy(() -> ChannelAttemptRecorder.requireTrueRefundReplay(attempts, "PM-C7", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("carries no channelReference")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }
}
