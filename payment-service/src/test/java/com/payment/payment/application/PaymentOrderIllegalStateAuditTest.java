package com.payment.payment.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.channel.MockChannelAdapter;
import com.payment.payment.support.PaymentTestStack;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单侧「非法前态拒绝」的留痕（spec 002 / T024 / FR-009）：
 * 支付已成功但订单不在可接收状态（已支付 / 已关闭等）是**资金风险信号**——钱收了、订单不认。
 * 它必须与「RPC 抖动」区分开：前者需要人工介入与对账兜底，后者重试即可收敛。
 *
 * <p>两者都<b>不得回滚支付成功事实</b>（订单侧幂等 + 后续对账收敛）。</p>
 */
class PaymentOrderIllegalStateAuditTest {

    private final PaymentTestStack stack = new PaymentTestStack();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        auditLogger = (Logger) LoggerFactory.getLogger("FINANCIAL_AUDIT");
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(appender);
        appender.stop();
    }

    private Payment unknownPayment() {
        // TIMEOUT 渠道 → UNKNOWN，随后由权威回调推进成功（此时才会通知 order）
        Payment payment = stack.appService(new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT))
                .createPaymentIntent(stack.command("k1"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        return payment;
    }

    private PaymentResultProcessor processor(OrderGateway orderGateway) {
        return new PaymentResultProcessor(stack.payments, stack.attempts, orderGateway,
                (key, paymentNo, amountMinor, feeMinor, currencyCode) -> {
                },
                new MicrometerBusinessMetrics(registry),
                new StructuredAuditLogger());
    }

    /** 抛指定错误码的订单网关替身。 */
    private static OrderGateway rejecting(String code) {
        return new OrderGateway() {
            @Override
            public void notifyPaymentSucceeded(PaymentSucceededRequest request) {
                throw BizException.of(code, "order rejected: " + code);
            }

            @Override
            public void notifyRefundResult(RefundResultNotification notification) {
                // 与本用例无关
            }
        };
    }

    private List<String> auditEvents() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void illegalOrderStateRejectionIsAuditedAndDoesNotRollbackSuccess() {
        Payment payment = unknownPayment();

        boolean changed = processor(rejecting(ErrorCodes.STATE_TRANSITION_VIOLATION))
                .applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-1"));

        assertThat(changed).isTrue();
        // 事实不回滚：支付仍是 SUCCEEDED
        assertThat(stack.payments.findByPaymentNo(payment.getPaymentNo()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        // 专用审计 + 专用指标（与通用通知失败指标并存）
        assertThat(auditEvents()).anyMatch(e -> e.contains("payment.order_illegal_state_rejected"));
        assertThat(registry.get("payment.order_illegal_state_rejected").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("payment.order_notify_failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void orderNotPayableIsAuditedAsIllegalState() {
        Payment payment = unknownPayment();

        processor(rejecting(ErrorCodes.ORDER_NOT_PAYABLE))
                .applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-2"));

        assertThat(auditEvents()).anyMatch(e -> e.contains("payment.order_illegal_state_rejected"));
        assertThat(registry.get("payment.order_illegal_state_rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void transientNotifyFailureIsNotAuditedAsIllegalState() {
        Payment payment = unknownPayment();
        OrderGateway flaky = new OrderGateway() {
            @Override
            public void notifyPaymentSucceeded(PaymentSucceededRequest request) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public void notifyRefundResult(RefundResultNotification notification) {
            }
        };

        boolean changed = processor(flaky).applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-3"));

        assertThat(changed).isTrue();
        assertThat(registry.get("payment.order_notify_failed").counter().count()).isEqualTo(1.0);
        // 抖动不算非法前态：不写资金审计、不递增专用指标
        assertThat(registry.find("payment.order_illegal_state_rejected").counter()).isNull();
        assertThat(auditEvents()).noneMatch(e -> e.contains("payment.order_illegal_state_rejected"));
    }

    @Test
    void successfulNotifyWritesNoAudit() {
        Payment payment = unknownPayment();

        processor(stack.order).applyAndNotify(payment.getPaymentNo(), ChannelResult.success("ref-4"));

        assertThat(registry.find("payment.order_notify_failed").counter()).isNull();
        assertThat(auditEvents()).noneMatch(e -> e.contains("payment.order_illegal_state_rejected"));
    }
}
