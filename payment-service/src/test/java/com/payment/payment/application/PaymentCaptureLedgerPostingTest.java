package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.channelgateway.infra.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 支付成功 → 账本记账断言测试（004 T010 收口 / spec 031 事件化改造 ADR-0077）：
 * 成功结果 MUST 恰好触发一次记账，payment 侧只传<b>已确认财务事实</b>
 * （{@link LedgerPostingGateway.PaymentCaptureFacts}：paymentNo + merchantId + 渠道码 + 金额），
 * 不再拼接幂等键——账本按 {@code PAYMENT_CAPTURE:{paymentNo}} 派生（原则 10）。
 *
 * <p>spec 030 / B1 的「两条路径同键」在新契约下升格为<b>「两条路径同 paymentNo 事实」</b>：
 * 同步成功路径（{@code PaymentApplicationService}）与回调收敛路径（{@code PaymentResultProcessor}）
 * MUST 传入<b>同一 paymentNo</b> 的 capture 事实，键由账本派生 ⇒ 天然无双前缀、永不重复记账。
 * 重复回调命中终态吸收不再记账；迟到失败不产生记账。</p>
 *
 * <p>注：按仓库既有约定用进程内 RecordingGateway 断言网关契约
 * （账本侧借贷平衡已由 ledger-service 记账测试覆盖），未引入 Testcontainers。</p>
 */
class PaymentCaptureLedgerPostingTest {

    private final PaymentTestStack stack = new PaymentTestStack();
    private final RecordingLedgerGateway ledger = new RecordingLedgerGateway();

    /** 记录式记账网关：捕获已确认支付事实 {@link PaymentCaptureFacts}（不真正触达账本）。 */
    private static final class RecordingLedgerGateway implements LedgerPostingGateway {

        final List<PaymentCaptureFacts> calls = new ArrayList<>();

        @Override
        public void postPaymentCapture(PaymentCaptureFacts facts) {
            calls.add(facts);
        }
    }

    private PaymentResultProcessor ledgerWiredProcessor() {
        return new PaymentResultProcessor(stack.payments, stack.attempts, stack.order, ledger);
    }

    @Test
    void successResultPostsLedgerOnceWithPaymentNoAndFullAmount() {
        // TIMEOUT 渠道 → UNKNOWN（UNKNOWN 不记账）
        Payment payment = stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(ledger.calls).isEmpty();

        // 权威回调成功 → 恰好一次记账
        boolean changed = ledgerWiredProcessor().applyAndNotify(
                payment.getPaymentNo(), ChannelResult.success("ref-cb"));
        assertThat(changed).isTrue();
        assertThat(ledger.calls).hasSize(1);

        LedgerPostingGateway.PaymentCaptureFacts facts = ledger.calls.get(0);
        assertThat(facts.paymentNo()).isEqualTo(payment.getPaymentNo());    // sourceId = 业务单号
        assertThat(facts.merchantId()).isEqualTo("M001");                  // 商户号事实锚
        assertThat(facts.channelCode()).isEqualTo("mock");                 // 成功 attempt 的渠道码
        assertThat(facts.grossAmountMinor()).isEqualTo(100L);              // 用户实付毛额
        assertThat(facts.merchantFeeMinor()).isZero();                     // 商户费 MVP 恒 0
        assertThat(facts.channelFeeMinor()).isZero();                      // 渠道费 MVP 恒 0
        assertThat(facts.currencyCode()).isEqualTo("CNY");
    }

    @Test
    void duplicateSuccessCallbackDoesNotRepostLedger() {
        Payment payment = stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(stack.command("k1"));
        PaymentResultProcessor processor = ledgerWiredProcessor();

        processor.applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-cb"));
        processor.applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-cb")); // 重复回调

        assertThat(ledger.calls).hasSize(1);
    }

    @Test
    void lateFailureCallbackDoesNotPostLedger() {
        Payment payment = stack.appService(new MockChannelAdapter())
                .createPaymentIntent(stack.command("k1")); // 渠道直接成功
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        // 兼容构造不接账本 → 成功路径已被空网关吸收；此处用带账本的处理器验证迟到失败
        PaymentResultProcessor processor = ledgerWiredProcessor();

        boolean changed = processor.applyAndNotify(
                payment.getPaymentNo(), ChannelResult.businessFailure("ref", "late decline"));

        assertThat(changed).isFalse();
        assertThat(ledger.calls).isEmpty();
    }

    // =========================================================================
    // spec 030 / B1（T10 / T11）+ spec 031：两条路径同 paymentNo 事实
    // =========================================================================

    /**
     * FR-221 / SC-B1-01、SC-B1-02：同步成功路径与回调收敛路径 MUST 传入<b>同一 paymentNo</b> 的
     * capture 事实（键由账本派生 ⇒ 天然无双前缀）。
     */
    @Test
    void bothSuccessPathsPostCaptureForSamePaymentNo() {
        // ① 同步成功路径：渠道直接成功
        Payment syncPayment = stack.appService(new MockChannelAdapter(), ledger)
                .createPaymentIntent(stack.command("k-sync"));
        assertThat(syncPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(ledger.calls).hasSize(1);
        LedgerPostingGateway.PaymentCaptureFacts syncFacts = ledger.calls.get(0);

        // ② 回调收敛路径：TIMEOUT → UNKNOWN → 回调成功
        PaymentTestStack cbStack = new PaymentTestStack();
        Payment cbPayment = cbStack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(cbStack.command("k-cb"));
        assertThat(cbPayment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        new PaymentResultProcessor(cbStack.payments, cbStack.attempts, cbStack.order, ledger)
                .applyAndNotify(cbPayment.getPaymentNo(), ChannelResult.success("ref-cb"));

        assertThat(ledger.calls).hasSize(2);
        LedgerPostingGateway.PaymentCaptureFacts callbackFacts = ledger.calls.get(1);

        // 两路各自传入自己 payment 的 paymentNo，且都带商户号事实（sourceId 无手工前缀）
        assertThat(syncFacts.paymentNo()).isEqualTo(syncPayment.getPaymentNo());
        assertThat(callbackFacts.paymentNo()).isEqualTo(cbPayment.getPaymentNo());
        assertThat(syncFacts.merchantId()).isEqualTo("M001");
        assertThat(callbackFacts.merchantId()).isEqualTo("M001");
    }

    /**
     * FR-220：同步路径传入的 capture 事实以<b>裸 paymentNo</b> 为 sourceId，
     * 不再是支付幂等键（幂等键由账本派生，payment 侧不传）。
     */
    @Test
    void syncPathCarriesPaymentNoNotIdempotencyKey() {
        Payment payment = stack.appService(new MockChannelAdapter(), ledger)
                .createPaymentIntent(stack.command("k-bare"));

        assertThat(ledger.calls).hasSize(1);
        LedgerPostingGateway.PaymentCaptureFacts facts = ledger.calls.get(0);
        assertThat(facts.paymentNo()).isEqualTo(payment.getPaymentNo());
        assertThat(facts.paymentNo()).isNotEqualTo(payment.getIdempotencyKey());
    }

    /**
     * FR-223 / SC-B1-03：同一支付单<b>先同步成功、再收到回调</b> ⇒ capture 事实数 <b>= 1</b>（不是 2）。
     *
     * <p>回调命中终态吸收（changed=false）不再记账。</p>
     */
    @Test
    void syncSuccessThenCallbackProducesExactlyOnePosting() {
        Payment payment = stack.appService(new MockChannelAdapter(), ledger)
                .createPaymentIntent(stack.command("k-both"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(ledger.calls).hasSize(1);

        // 回调到达：已是终态 ⇒ 终态吸收，changed=false，不重复记账
        boolean changed = ledgerWiredProcessor().applyAndNotify(
                payment.getPaymentNo(), ChannelResult.success("ref-late"));

        assertThat(changed).isFalse();
        assertThat(ledger.calls).hasSize(1);
        assertThat(ledger.calls.get(0).paymentNo()).isEqualTo(payment.getPaymentNo());
    }
}
