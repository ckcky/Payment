package com.payment.payment.infra.persistence;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.domain.Payment;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderRepository;
import com.payment.channelgateway.domain.ChannelOrderStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.PaymentPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付/支付尝试持久化集成测试（H2，MySQL 兼容模式）：验证 PO↔领域映射、审计字段、幂等键/渠道引用唯一、乐观锁。
 */
@SpringBootTest
class PaymentPersistenceTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ChannelOrderRepository attemptRepository;

    @Autowired
    private PaymentPersistence paymentPersistence;

    @Test
    void paymentRoundTrip() {
        Payment payment = new Payment("txn-rt", "order-rt", "user-rt", 100L, "CNY", "idem-rt");
        paymentRepository.save(payment);

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(payment.getId());
        assertThat(reloaded.getTransactionId()).isEqualTo("txn-rt");
        assertThat(reloaded.getOrderNo()).isEqualTo("order-rt");
        assertThat(reloaded.getUserId()).isEqualTo("user-rt");
        assertThat(reloaded.getAmountMinor()).isEqualTo(100L);
        assertThat(reloaded.getCurrencyCode()).isEqualTo("CNY");
        assertThat(reloaded.getIdempotencyKey()).isEqualTo("idem-rt");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(paymentRepository.findByTransactionId("txn-rt")).isPresent();
    }

    @Test
    void attemptRoundTrip() {
        Payment payment = new Payment("txn-att", "order-att", "user-att", 100L, "CNY", "idem-att");
        paymentRepository.save(payment);

        ChannelOrder attempt = new ChannelOrder(payment.getPaymentNo(), "mock", 0,
                payment.getAmountMinor(), payment.getCurrencyCode());
        attempt.accept("ref-att");
        attemptRepository.save(attempt);

        ChannelOrder reloaded = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(attempt.getId());
        assertThat(reloaded.getPaymentNo()).isEqualTo(payment.getPaymentNo());
        assertThat(reloaded.getChannelCode()).isEqualTo("mock");
        assertThat(reloaded.getChannelReference()).isEqualTo("ref-att");
        assertThat(reloaded.getStatus()).isEqualTo(ChannelOrderStatus.ACCEPTED);
        assertThat(reloaded.getRetryCount()).isEqualTo(0);
        assertThat(reloaded.getVersion()).isEqualTo(1);
        // spec 018 / US1：尝试金额留痕（PAYMENT=支付单金额）
        assertThat(reloaded.getAmountMinor()).isEqualTo(100L);
        assertThat(reloaded.getCurrencyCode()).isEqualTo("CNY");

        assertThat(attemptRepository.findByPaymentNo(payment.getPaymentNo())).hasSize(1);
    }

    @Test
    void optimisticLockRejectsStaleUpdate() {
        Payment payment = new Payment("txn-lock", "order-lock", "user-lock", 100L, "CNY", "idem-lock");
        paymentRepository.save(payment);

        Payment first = paymentRepository.findById(payment.getId()).orElseThrow();
        Payment second = paymentRepository.findById(payment.getId()).orElseThrow();

        first.start(1L);
        first.succeed();
        paymentRepository.save(first);

        second.start(2L);
        second.succeed();
        assertThatThrownBy(() -> paymentRepository.save(second))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void insertPendingWritesPaymentOnlyAndLeavesChannelOrderToChannelDomain() {
        CreatePaymentCommand cmd = new CreatePaymentCommand("txn-ip", "order-ip", "user-ip",
                250L, "USD", "idem-ip", "mock", "M001");
        // Feature 028 / FR-028：insertPending 新增 routedChannelCode 形参（路由后最终渠道码）
        PaymentPersistence.PendingPayment pending = paymentPersistence.insertPending(cmd, "MOCK");
        assertThat(pending.created()).isTrue();

        // spec 041：支付单落库金额，且**不开渠道单**——渠道单是渠道层的订单，
        // 由 ChannelOrderService.openChannelOrder 在渠道侧事务内开立。
        // 改造前这里会顺带开出一条 PAYMENT 渠道单并记金额（本类原断言即为此）；
        // 该职责已移交渠道域，故此处反向断言「insertPending 绝不代开渠道单」。
        Payment stored = paymentRepository.findById(pending.payment().getId()).orElseThrow();
        assertThat(stored.getAmountMinor()).isEqualTo(250L);
        assertThat(stored.getCurrencyCode()).isEqualTo("USD");
        assertThat(attemptRepository.findByPaymentNo(pending.payment().getPaymentNo()))
                .as("insertPending MUST NOT 代渠道域开渠道单（spec 041）")
                .isEmpty();
    }
}
