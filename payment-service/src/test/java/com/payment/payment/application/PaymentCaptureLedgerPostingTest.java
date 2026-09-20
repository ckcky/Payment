package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 支付成功 → 账本记账断言测试（004 T010 收口）：
 * 成功结果 MUST 恰好触发一次记账，幂等键格式 {@code PAYMENT:<paymentNo>}（Feature 015 / C2），
 * sourceId 用业务单号 paymentNo（ADR-0063），金额为支付全额、手续费 0；
 * 重复回调不重复记账；迟到失败不产生记账。
 *
 * <p>spec 030 / B1（FR-220~FR-223，T10/T11）：新增「两条路径同键」断言——
 * <b>同步成功路径</b>（{@code PaymentApplicationService}）与<b>回调收敛路径</b>
 * （{@code PaymentResultProcessor}）MUST 产生<b>同一个</b> postingKey
 * {@code PAYMENT:{paymentNo}}。修复前前者传 {@code payment.getIdempotencyKey()}、
 * 后者传 {@code "PAYMENT:" + paymentNo}，而 {@code FeignLedgerPostingGateway} 还会
 * 再拼一次前缀 ⇒ 回调路径实得 {@code PAYMENT:PAYMENT:{paymentNo}}，两键不同，
 * 账本唯一约束无法吸收 ⇒ 同一笔支付产生两笔分录（重复记账）。</p>
 *
 * <p>注：按仓库既有约定用进程内 RecordingGateway 断言网关契约
 * （账本侧借贷平衡已由 ledger-service 的 LedgerPostingServiceTest 覆盖），未引入 Testcontainers。</p>
 */
class PaymentCaptureLedgerPostingTest {

    private final PaymentTestStack stack = new PaymentTestStack();
    private final RecordingLedgerGateway ledger = new RecordingLedgerGateway();

    /**
     * 记录式记账网关：捕获 (幂等键, paymentNo, 金额, 手续费, 币种)。
     *
     * <p>spec 030 / B1：{@code postingKeys} 模拟 {@code FeignLedgerPostingGateway} 的
     * 前缀拼接（{@code "PAYMENT:" + idempotencyKey}）——前缀拼接在生产代码里只此一处（T9），
     * 这里照同样规则记录，才能让「两条路径是否同键」被测出来。</p>
     */
    private static final class RecordingLedgerGateway implements LedgerPostingGateway {

        final List<String> calls = new ArrayList<>();
        /** 按生产 Gateway 规则拼出的最终 postingKey。 */
        final List<String> postingKeys = new ArrayList<>();

        @Override
        public void postPaymentCapture(String idempotencyKey, String paymentNo, long amountMinor,
                                       long feeMinor, String currencyCode) {
            calls.add(idempotencyKey + "|" + paymentNo + "|" + amountMinor + "|" + feeMinor + "|" + currencyCode);
            postingKeys.add("PAYMENT:" + idempotencyKey);
        }
    }

    private PaymentResultProcessor ledgerWiredProcessor() {
        return new PaymentResultProcessor(stack.payments, stack.attempts, stack.order, ledger);
    }

    @Test
    void successResultPostsLedgerOnceWithPaymentNoKeyAndFullAmount() {
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

        String[] parts = ledger.calls.get(0).split("\\|");
        // spec 030 / B1：传给 Gateway 的是**裸 paymentNo**，前缀由 Gateway 独占拼接
        assertThat(parts[0]).isEqualTo(payment.getPaymentNo());                 // 入参 = paymentNo（无前缀）
        assertThat(ledger.postingKeys.get(0))
                .isEqualTo("PAYMENT:" + payment.getPaymentNo());                // 最终键 PAYMENT:{paymentNo}
        assertThat(parts[1]).isEqualTo(payment.getPaymentNo());                 // sourceId = 业务单号
        assertThat(parts[2]).isEqualTo("100");                                  // 支付全额
        assertThat(parts[3]).isEqualTo("0");                                    // 手续费 0
        assertThat(parts[4]).isEqualTo("CNY");
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
    // spec 030 / B1（T10 / T11）：账本幂等键口径统一
    // =========================================================================

    /**
     * FR-221 / SC-B1-01、SC-B1-02：<b>同步成功路径</b>与<b>回调收敛路径</b> MUST 产生
     * <b>同一个</b> postingKey {@code PAYMENT:{paymentNo}}。
     *
     * <p>这是 B1 的核心回归断言：修复前同步路径传 {@code idempotencyKey}（支付幂等键）、
     * 回调路径传 {@code "PAYMENT:" + paymentNo}（再被 Gateway 拼一次 ⇒ 双前缀），
     * 两键不同 ⇒ 账本唯一约束吸收失败 ⇒ 一支付两分录。</p>
     */
    @Test
    void bothSuccessPathsProduceTheSamePostingKey() {
        // ① 同步成功路径：渠道直接成功
        Payment syncPayment = stack.appService(new MockChannelAdapter(), ledger)
                .createPaymentIntent(stack.command("k-sync"));
        assertThat(syncPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(ledger.postingKeys).hasSize(1);
        String syncKey = ledger.postingKeys.get(0);

        // ② 回调收敛路径：TIMEOUT → UNKNOWN → 回调成功
        PaymentTestStack cbStack = new PaymentTestStack();
        Payment cbPayment = cbStack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(cbStack.command("k-cb"));
        assertThat(cbPayment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        new PaymentResultProcessor(cbStack.payments, cbStack.attempts, cbStack.order, ledger)
                .applyAndNotify(cbPayment.getPaymentNo(), ChannelResult.success("ref-cb"));

        assertThat(ledger.postingKeys).hasSize(2);
        String callbackKey = ledger.postingKeys.get(1);

        // 两条路径的键形如 PAYMENT:{paymentNo}，且各自对得上自己的 paymentNo
        assertThat(syncKey).isEqualTo("PAYMENT:" + syncPayment.getPaymentNo());
        assertThat(callbackKey).isEqualTo("PAYMENT:" + callbackKey.substring("PAYMENT:".length()));
        assertThat(callbackKey).isEqualTo("PAYMENT:" + cbPayment.getPaymentNo());
        // 同形（都无双前缀）——这是「口径统一」的可断言形态
        assertThat(syncKey).startsWith("PAYMENT:");
        assertThat(callbackKey).startsWith("PAYMENT:");
        assertThat(callbackKey).doesNotContain("PAYMENT:PAYMENT:");
        assertThat(syncKey).doesNotContain("PAYMENT:PAYMENT:");
    }

    /**
     * FR-220：同步路径传给 Gateway 的<b>入参</b>是裸 {@code paymentNo}，
     * 不再是 {@code payment.getIdempotencyKey()}（避免与回调路径口径分裂）。
     */
    @Test
    void syncPathPassesBarePaymentNoNotIdempotencyKey() {
        Payment payment = stack.appService(new MockChannelAdapter(), ledger)
                .createPaymentIntent(stack.command("k-bare"));

        assertThat(ledger.calls).hasSize(1);
        String[] parts = ledger.calls.get(0).split("\\|");
        assertThat(parts[0]).isEqualTo(payment.getPaymentNo());
        // 显式否定旧口径：入参既不是支付幂等键，也不带手工前缀
        assertThat(parts[0]).isNotEqualTo(payment.getIdempotencyKey());
        assertThat(parts[0]).doesNotStartWith("PAYMENT:");
    }

    /**
     * FR-223 / SC-B1-03：同一支付单<b>先同步成功、再收到回调</b> ⇒ 账本分录数 <b>= 1</b>（不是 2）。
     *
     * <p>修复前两键不同，回调路径会再插一条分录；修复后回调命中终态吸收（changed=false）不再记账。</p>
     */
    @Test
    void syncSuccessThenCallbackProducesExactlyOnePosting() {
        Payment payment = stack.appService(new MockChannelAdapter(), ledger)
                .createPaymentIntent(stack.command("k-both"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(ledger.postingKeys).hasSize(1);

        // 回调到达：已是终态 ⇒ 终态吸收，changed=false，不重复记账
        boolean changed = ledgerWiredProcessor().applyAndNotify(
                payment.getPaymentNo(), ChannelResult.success("ref-late"));

        assertThat(changed).isFalse();
        assertThat(ledger.postingKeys).hasSize(1);
        assertThat(ledger.postingKeys.get(0)).isEqualTo("PAYMENT:" + payment.getPaymentNo());
    }
}
