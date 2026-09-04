package com.payment.payment.application;

import com.payment.payment.api.dto.PaymentFactResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付事实抽取测试（US3 对账）：仅返回已确认成功的支付事实，并携带渠道引用。
 */
class PaymentFactsServiceTest {

    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();

    @Test
    void confirmedFactsReturnsOnlySucceededPayments() {
        Payment succeeded = new Payment("txn-1", "order-1", "user-1", 100, "CNY", "idem-1");
        succeeded = payments.save(succeeded);
        PaymentAttempt attempt = new PaymentAttempt(succeeded.getPaymentNo(), "mock", 0);
        attempt = attempts.save(attempt);
        attempt.accept("channel-ref-1");
        succeeded.start(attempt.getId());
        succeeded.succeed();
        payments.save(succeeded);
        attempts.save(attempt);

        Payment failed = new Payment("txn-2", "order-2", "user-1", 200, "CNY", "idem-2");
        failed = payments.save(failed);
        PaymentAttempt failedAttempt = new PaymentAttempt(failed.getPaymentNo(), "mock", 0);
        failedAttempt = attempts.save(failedAttempt);
        failed.start(failedAttempt.getId());
        failed.fail("declined");
        payments.save(failed);

        PaymentFactsService service = new PaymentFactsService(payments, attempts);
        List<PaymentFactResponse> facts = service.confirmedFacts();

        assertThat(facts).hasSize(1);
        PaymentFactResponse fact = facts.get(0);
        assertThat(fact.paymentNo()).isEqualTo(succeeded.getPaymentNo());
        assertThat(fact.channelReference()).isEqualTo("channel-ref-1");
        assertThat(fact.amountMinor()).isEqualTo(100L);
        assertThat(fact.currencyCode()).isEqualTo("CNY");
        assertThat(fact.status()).isEqualTo("SUCCEEDED");
    }
}
