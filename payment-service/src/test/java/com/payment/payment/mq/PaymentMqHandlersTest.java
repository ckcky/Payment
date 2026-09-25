package com.payment.payment.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.TransactionChecker;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.Refund;
import com.payment.payment.domain.RefundRepository;
import com.payment.payment.domain.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * payment 消费侧路由 + 回查 checker 单测（spec 029 / 批次 C / T31、T49 / SC-9）。
 *
 * <p>三个关键正确性判据：</p>
 * <ol>
 *   <li><b>关单只关未成功的</b>：{@code order.cancelled} 关闭 PENDING/PROCESSING/UNKNOWN，
 *       但**绝不**关闭 SUCCEEDED——钱已收，此时关单会让订单误判支付状态（INV-1 事实不回滚）。</li>
 *   <li><b>回查判据取业务库</b>：checker 必须查数据库判定（不得以 Redis 内状态自证），
 *       且「查不到」要判 ROLLBACK（本地事务未提交，消息不该投递）。</li>
 *   <li><b>订单状态 PAYMENT_SUCCEEDED 视为已提交</b>：支付单 SUCCEEDED **或** CLOSED 都判 COMMIT
 *       （CLOSED 说明关单事实已落库，通知订单同样是正确终态）。</li>
 * </ol>
 */
class PaymentMqHandlersTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PaymentMqHandlers handlers =
            new PaymentMqHandlers(paymentRepository, refundRepository);

    private static EventEnvelope envelope(String topic, String bizNo, Map<String, Object> payload) {
        return new EventEnvelope("msg-" + bizNo, topic, topic.toUpperCase().replace('.', '_'),
                bizNo, "trace-" + bizNo, "order-service", Instant.now(), payload);
    }

    /** 建一张真实 PENDING 支付单（走真实构造器，不 mock 聚合）。 */
    private static Payment pendingPayment() {
        return new Payment("TX-1", "ORD-1", "u-1", 9900L, "CNY", "idem-1");
    }

    // ---- T49：消费 order.cancelled ----

    @Test
    @DisplayName("T49 order.cancelled → 关闭 PENDING 支付单并落库")
    void onOrderCancelledClosesPending() {
        Payment p = pendingPayment();
        when(paymentRepository.findByOrderNo("ORD-1")).thenReturn(List.of(p));

        handlers.onOrderCancelled(envelope("order.cancelled", "ORD-1", Map.of("orderNo", "ORD-1")));

        assertThat(p.getStatus()).as("PENDING → CLOSED").isEqualTo(PaymentStatus.CLOSED);
        assertThat(p.getFailureReason()).contains("order cancelled");
        verify(paymentRepository).save(p);
    }

    @Test
    @DisplayName("T49 已 SUCCEEDED 的支付单不被关闭（钱已收，surplus 归 order 处置，INV-1）")
    void onOrderCancelledDoesNotCloseSucceeded() {
        Payment p = pendingPayment();
        p.start(1L);          // PENDING → PROCESSING
        assertThat(p.succeed()).isTrue();
        when(paymentRepository.findByOrderNo("ORD-1")).thenReturn(List.of(p));

        handlers.onOrderCancelled(envelope("order.cancelled", "ORD-1", Map.of("orderNo", "ORD-1")));

        assertThat(p.getStatus()).as("SUCCEEDED 不变").isEqualTo(PaymentStatus.SUCCEEDED);
        verify(paymentRepository, never()).save(p);
    }

    @Test
    @DisplayName("T49 缺 orderNo → 安全忽略，不查库不落库")
    void onOrderCancelledSkipsWithoutOrderNo() {
        handlers.onOrderCancelled(envelope("order.cancelled", "X", Map.of()));

        verify(paymentRepository, never()).findByOrderNo(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("T49 幂等：重复投递时第二次不再落库（状态已是 CLOSED，close 返回 false）")
    void onOrderCancelledIsIdempotent() {
        Payment p = pendingPayment();
        when(paymentRepository.findByOrderNo("ORD-1")).thenReturn(List.of(p));
        EventEnvelope e = envelope("order.cancelled", "ORD-1", Map.of("orderNo", "ORD-1"));

        handlers.onOrderCancelled(e);
        handlers.onOrderCancelled(e);

        verify(paymentRepository, times(1)).save(p);
    }

    // ---- T31：回查 checker ----

    @Test
    @DisplayName("T31 payment.succeeded checker：SUCCEEDED → COMMIT")
    void paymentSucceededCheckerCommitsWhenSucceeded() {
        Payment p = pendingPayment();
        p.start(1L);
        p.succeed();
        when(paymentRepository.findByPaymentNo(p.getPaymentNo())).thenReturn(Optional.of(p));

        TransactionChecker checker = handlers.paymentSucceededChecker();
        EventEnvelope e = envelope(MqTopics.PAYMENT_SUCCEEDED, "PAY-1",
                Map.of("paymentNo", p.getPaymentNo()));

        assertThat(checker.supports(e)).isTrue();
        assertThat(checker.check(e)).isEqualTo(TransactionChecker.LocalTxState.COMMIT);
    }

    @Test
    @DisplayName("T31 payment.succeeded checker：CLOSED 亦判 COMMIT（关单事实已落库）")
    void paymentSucceededCheckerTreatsClosedAsCommitted() {
        Payment p = pendingPayment();
        p.closeByOrderCancelled();
        when(paymentRepository.findByPaymentNo(p.getPaymentNo())).thenReturn(Optional.of(p));

        TransactionChecker checker = handlers.paymentSucceededChecker();
        EventEnvelope e = envelope(MqTopics.PAYMENT_SUCCEEDED, "PAY-1",
                Map.of("paymentNo", p.getPaymentNo()));

        assertThat(checker.check(e)).isEqualTo(TransactionChecker.LocalTxState.COMMIT);
    }

    @Test
    @DisplayName("T31 payment.succeeded checker：PENDING（未决）→ ROLLBACK")
    void paymentSucceededCheckerRollsBackWhenNotTerminal() {
        Payment p = pendingPayment();   // 仍 PENDING
        when(paymentRepository.findByPaymentNo(p.getPaymentNo())).thenReturn(Optional.of(p));

        TransactionChecker checker = handlers.paymentSucceededChecker();
        EventEnvelope e = envelope(MqTopics.PAYMENT_SUCCEEDED, "PAY-1",
                Map.of("paymentNo", p.getPaymentNo()));

        assertThat(checker.check(e)).isEqualTo(TransactionChecker.LocalTxState.ROLLBACK);
    }

    @Test
    @DisplayName("T31 payment.succeeded checker：查不到 → ROLLBACK（本地事务未提交）")
    void paymentSucceededCheckerRollsBackWhenMissing() {
        when(paymentRepository.findByPaymentNo("PAY-9")).thenReturn(Optional.empty());

        TransactionChecker checker = handlers.paymentSucceededChecker();
        EventEnvelope e = envelope(MqTopics.PAYMENT_SUCCEEDED, "PAY-9", Map.of("paymentNo", "PAY-9"));

        assertThat(checker.check(e)).isEqualTo(TransactionChecker.LocalTxState.ROLLBACK);
    }

    @Test
    @DisplayName("T31 payment.succeeded checker：非本 topic 不接管（supports=false）")
    void paymentSucceededCheckerIgnoresOtherTopics() {
        TransactionChecker checker = handlers.paymentSucceededChecker();
        assertThat(checker.supports(envelope(MqTopics.ORDER_PAID, "ORD-6", Map.of()))).isFalse();
    }

    @Test
    @DisplayName("T31 refund.result checker：退款终态 SUCCEEDED → COMMIT")
    void refundResultCheckerCommitsOnTerminal() {
        Refund r = new Refund("ORD-1", "PAY-1", "u-1", 9900L, "CNY", "test", "idem-r", List.of());
        r.process();
        assertThat(r.succeed()).isTrue();
        when(refundRepository.findByRefundNo(r.getRefundNo())).thenReturn(Optional.of(r));

        TransactionChecker checker = handlers.refundResultChecker();
        // 注意：退款回查读的是 paymentRefundNo 字段（PMRF），不是 refundNo
        EventEnvelope e = envelope(MqTopics.REFUND_RESULT, "PMRF-1",
                Map.of("paymentRefundNo", r.getRefundNo()));

        assertThat(checker.supports(e)).isTrue();
        assertThat(checker.check(e)).isEqualTo(TransactionChecker.LocalTxState.COMMIT);
    }

    @Test
    @DisplayName("T31 refund.result checker：退款未决 PROCESSING → ROLLBACK")
    void refundResultCheckerRollsBackWhenNotTerminal() {
        Refund r = new Refund("ORD-1", "PAY-1", "u-1", 9900L, "CNY", "test", "idem-r", List.of());
        r.process();   // 停在 PROCESSING
        when(refundRepository.findByRefundNo(r.getRefundNo())).thenReturn(Optional.of(r));

        TransactionChecker checker = handlers.refundResultChecker();
        EventEnvelope e = envelope(MqTopics.REFUND_RESULT, "PMRF-1",
                Map.of("paymentRefundNo", r.getRefundNo()));

        assertThat(checker.check(e)).isEqualTo(TransactionChecker.LocalTxState.ROLLBACK);
    }

    @Test
    @DisplayName("T31 refund.result checker：查不到 / 缺 paymentRefundNo → ROLLBACK")
    void refundResultCheckerRollsBackWhenMissing() {
        when(refundRepository.findByRefundNo("PMRF-2")).thenReturn(Optional.empty());

        TransactionChecker checker = handlers.refundResultChecker();
        assertThat(checker.check(envelope(MqTopics.REFUND_RESULT, "PMRF-2",
                Map.of("paymentRefundNo", "PMRF-2"))))
                .isEqualTo(TransactionChecker.LocalTxState.ROLLBACK);
        assertThat(checker.check(envelope(MqTopics.REFUND_RESULT, "X", Map.of())))
                .as("缺 paymentRefundNo 直接判 ROLLBACK，不查库")
                .isEqualTo(TransactionChecker.LocalTxState.ROLLBACK);
    }
}
