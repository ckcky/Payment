package com.payment.payment.application;

import com.payment.common.dto.channel.ChannelPayNotified;
import com.payment.common.dto.channel.ChannelPayStatus;
import com.payment.common.dto.channel.ChannelRefundNotified;
import com.payment.common.dto.channel.ChannelRefundStatus;
import com.payment.payment.domain.Payment;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderStatus;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.support.PaymentTestStack;
import com.payment.payment.support.RecordingObservability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 037 / T5 / FR-010 / FR-011 / FR-012 / SC-004：渠道 → Payment 的<b>入向端口</b>。
 *
 * <p>本类钉住三件事：
 * <ol>
 *   <li><b>定义权方向（INV-2 / FR-012）</b>：端口由 <b>Payment 定义</b>（位于
 *       {@code com.payment.payment.application}），渠道网关只依赖接口——取代
 *       {@code RefundResultListener}（定义在渠道包、实现在退款应用服务的反向形态）；</li>
 *   <li><b>契约纯度（SC-004）</b>：入向事件携带 {@code channelCode}（FR-006），
 *       且分量类型<b>全部来自 {@code common-dto} / JDK</b>，不含任何渠道网关私有类型；</li>
 *   <li><b>业务校验归属（FR-011）</b>：原先写在 {@code ChannelPluginCallbackController.validate()}
 *       里的 Payment 业务校验（查单 / 验归属 / 验金额币种）搬进本端口的实现，
 *       校验失败的「三件套」（不推进 + 计指标 + 写审计）保持逐字同口径。</li>
 * </ol>
 */
class PaymentNotifyPortTest {

    private static final String PAYMENT_NO = "PM-NOTIFY-1";

    private PaymentTestStack stack;
    private RecordingObservability obs;
    private DefaultPaymentNotifyPort port;

    @BeforeEach
    void setUp() {
        stack = new PaymentTestStack();
        obs = new RecordingObservability();

        stack.payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "CNY", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));
        stack.attempts.save(ChannelOrder.rehydrate(10L, PAYMENT_NO, "ALIPAY", 0,
                Instant.now().minusSeconds(60), null, null, ChannelOrderStatus.ACCEPTED,
                null, null, 1, "PAYMENT", 10_00L, "CNY",
                Map.of(ChannelOrder.CHANNEL_MODE_KEY, "SANDBOX")));

        // 退款侧协作者置 null：本类只验支付回调路径（退款路径由既有退款单测覆盖）
        port = new DefaultPaymentNotifyPort(stack.callback, null,
                stack.payments, stack.attempts, obs.metrics, obs.audit);
    }

    private static ChannelPayNotified notified(ChannelPayStatus status, String channelRef,
                                               Long amountMinor, String currency) {
        return new ChannelPayNotified(null, PAYMENT_NO, "ALIPAY", status, channelRef,
                amountMinor, currency, null, Instant.now());
    }

    // ===================== 契约层（定义权方向 / 纯度） =====================

    @Test
    @DisplayName("INV-2 / FR-012：入向端口由 Payment 定义（位于 payment.application）")
    void portIsDefinedByPayment() {
        assertThat(PaymentNotifyPort.class.getPackageName())
                .as("接口定义权必须归 Payment；定义在渠道包即 INV-2 倒置")
                .isEqualTo("com.payment.payment.application");
    }

    @Test
    @DisplayName("FR-010：端口只暴露两条入向操作 onChannelPayResult / onChannelRefundResult")
    void portExposesExactlyTheTwoInboundOperations() {
        List<String> names = Arrays.stream(PaymentNotifyPort.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .map(Method::getName)
                .sorted()
                .collect(Collectors.toList());

        assertThat(names).containsExactly("onChannelPayResult", "onChannelRefundResult");
    }

    @Test
    @DisplayName("FR-006：两条入向事件都 MUST 携带 channelCode")
    void notifiedEventsCarryChannelCode() {
        assertThat(componentNames(ChannelPayNotified.class)).contains("channelCode");
        assertThat(componentNames(ChannelRefundNotified.class)).contains("channelCode");
    }

    @Test
    @DisplayName("SC-004：入向事件分量全部来自 common-dto / JDK，不含渠道网关私有类型")
    void notifiedEventsCarryNoChannelPrivateTypes() {
        for (Class<?> contract : List.of(ChannelPayNotified.class, ChannelRefundNotified.class)) {
            for (RecordComponent component : contract.getRecordComponents()) {
                String pkg = component.getType().getPackageName();
                assertThat(pkg)
                        .as("%s#%s 的类型 %s 不得来自渠道网关域或 payment 内部实现",
                                contract.getSimpleName(), component.getName(), component.getType().getName())
                        .doesNotStartWith("com.payment.channelgateway")
                        .doesNotStartWith("com.payment.payment");
            }
        }
    }

    // ===================== 行为层（FR-011：业务校验迁入本实现） =====================

    @Test
    @DisplayName("FR-011：金额不符 ⇒ 拒绝 + 不推进 + 三件套留痕（口径与旧 validate 逐字一致）")
    void amountMismatchIsRejectedWithTripleTrace() {
        PayNotifyOutcome outcome = port.onChannelPayResult(
                notified(ChannelPayStatus.SUCCESS, "ch-1", 99_99L, "CNY"));

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.REJECTED);
        assertThat(outcome.detail()).startsWith("amount mismatch");
        assertThat(stack.payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .as("拒绝不留半写").isEqualTo(PaymentStatus.PROCESSING);
        assertThat(obs.countOf("payment.notify_rejected", "reason", "amount")).isEqualTo(1);
        assertThat(obs.auditedAction("payment.notify_rejected")).isTrue();
    }

    @Test
    @DisplayName("FR-011：币种不符 ⇒ 拒绝并计入 currency 维度")
    void currencyMismatchIsRejected() {
        stack.payments.save(Payment.rehydrate(1L, PAYMENT_NO, "TX-1", "ORDER-1", "user-1",
                10_00L, "USD", "idem-1", PaymentStatus.PROCESSING, 10L, null, 0, null, 0, 1, "M001"));

        PayNotifyOutcome outcome = port.onChannelPayResult(
                notified(ChannelPayStatus.SUCCESS, "ch-1", 10_00L, "CNY"));

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.REJECTED);
        assertThat(obs.countOf("payment.notify_rejected", "reason", "currency")).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-011：渠道引用串号 ⇒ 拒绝并计入 channel_reference 维度")
    void conflictingChannelReferenceIsRejected() {
        ChannelOrder attempt = stack.attempts.findByPaymentNo(PAYMENT_NO).get(0);
        attempt.backfillChannelReference("ch-original");
        stack.attempts.save(attempt);

        PayNotifyOutcome outcome = port.onChannelPayResult(
                notified(ChannelPayStatus.SUCCESS, "ch-DIFFERENT", 10_00L, "CNY"));

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.REJECTED);
        assertThat(obs.countOf("payment.notify_rejected", "reason", "channel_reference")).isEqualTo(1);
    }

    @Test
    @DisplayName("受理时引用为空 ⇒ 首次通知是回填时机，不算不一致（不误拒）")
    void nullRecordedReferenceIsBackfillOpportunity() {
        PayNotifyOutcome outcome = port.onChannelPayResult(
                notified(ChannelPayStatus.SUCCESS, "ch-first", 10_00L, "CNY"));

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.ACCEPTED);
        assertThat(stack.payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(obs.countOf("payment.notify_rejected")).isZero();
    }

    @Test
    @DisplayName("校验通过 ⇒ 收敛成功且**无**拒绝痕迹（诚实拒绝的反面）")
    void acceptedConvergesWithoutRejectionTrace() {
        ChannelOrder attempt = stack.attempts.findByPaymentNo(PAYMENT_NO).get(0);
        attempt.backfillChannelReference("ch-1");
        stack.attempts.save(attempt);

        PayNotifyOutcome outcome = port.onChannelPayResult(
                notified(ChannelPayStatus.SUCCESS, "ch-1", 10_00L, "CNY"));

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.ACCEPTED);
        assertThat(stack.payments.findByPaymentNo(PAYMENT_NO).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(obs.countOf("payment.notify_rejected"))
                .as("校验通过绝不能留下拒绝痕迹，否则指标永久高位误导排障")
                .isZero();
    }

    @Test
    @DisplayName("金额缺失（未读到）⇒ 跳过金额校验，不产生拒绝")
    void missingAmountSkipsValidation() {
        PayNotifyOutcome outcome = port.onChannelPayResult(
                notified(ChannelPayStatus.SUCCESS, "ch-first", null, null));

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.ACCEPTED);
        assertThat(obs.countOf("payment.notify_rejected")).isZero();
    }

    @Test
    @DisplayName("单据不存在 ⇒ ERROR（不假装成功，让渠道重推）")
    void unknownPaymentYieldsError() {
        ChannelPayNotified orphan = new ChannelPayNotified(null, "PM-NOPE", "ALIPAY",
                ChannelPayStatus.SUCCESS, "ch-1", null, null, null, Instant.now());

        PayNotifyOutcome outcome = port.onChannelPayResult(orphan);

        assertThat(outcome.status()).isEqualTo(PayNotifyOutcome.Status.ERROR);
        assertThat(obs.countOf("payment.notify_rejected", "reason", "processing_error")).isEqualTo(1);
    }

    @Test
    @DisplayName("退款结论映射：三档结论与渠道流水号逐字保留")
    void refundNotificationIsMappedFaithfully() {
        ChannelRefundNotified refund = new ChannelRefundNotified(null, "PMRF-1", "ALIPAY",
                ChannelRefundStatus.SUCCESS, "ch-refund-1", null, null, null, Instant.now());

        assertThat(refund.status().isSuccess()).isTrue();
        assertThat(refund.refundNo()).isEqualTo("PMRF-1");
        assertThat(refund.channelCode()).isEqualTo("ALIPAY");
    }

    private static List<String> componentNames(Class<?> contract) {
        return Arrays.stream(contract.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }
}
