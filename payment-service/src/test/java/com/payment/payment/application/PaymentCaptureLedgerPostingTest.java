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
 * <p>注：按仓库既有约定用进程内 RecordingGateway 断言网关契约
 * （账本侧借贷平衡已由 ledger-service 的 LedgerPostingServiceTest 覆盖），未引入 Testcontainers。</p>
 */
class PaymentCaptureLedgerPostingTest {

    private final PaymentTestStack stack = new PaymentTestStack();
    private final RecordingLedgerGateway ledger = new RecordingLedgerGateway();

    /** 记录式记账网关：捕获 (幂等键, paymentNo, 金额, 手续费, 币种)。 */
    private static final class RecordingLedgerGateway implements LedgerPostingGateway {

        final List<String> calls = new ArrayList<>();

        @Override
        public void postPaymentCapture(String idempotencyKey, String paymentNo, long amountMinor,
                                       long feeMinor, String currencyCode) {
            calls.add(idempotencyKey + "|" + paymentNo + "|" + amountMinor + "|" + feeMinor + "|" + currencyCode);
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
        assertThat(parts[0]).isEqualTo("PAYMENT:" + payment.getPaymentNo()); // 幂等键 PAYMENT:{paymentNo}
        assertThat(parts[1]).isEqualTo(payment.getPaymentNo());              // sourceId = 业务单号
        assertThat(parts[2]).isEqualTo("100");                               // 支付全额
        assertThat(parts[3]).isEqualTo("0");                                 // 手续费 0
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
}
