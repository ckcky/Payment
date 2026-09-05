package com.payment.refund.application;

import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.refund.api.dto.RefundFactResponse;
import com.payment.refund.domain.Refund;
import com.payment.refund.infra.InMemoryRefundRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款事实抽取测试（US3 对账）：仅返回已确认成功的退款事实，外部引用为 {@code refund-{id}}。
 */
class RefundFactsServiceTest {

    private final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    private final InMemoryPaymentAttemptRepository paymentAttempts = new InMemoryPaymentAttemptRepository();

    @Test
    void confirmedFactsReturnsOnlySucceededRefunds() {
        Refund succeeded = new Refund("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
        succeeded.process();
        succeeded.succeed();
        refunds.save(succeeded);

        Refund failed = new Refund("order-2", "PM-2", "user-1", 500L, "CNY", "customer",
                "idem-2", List.of());
        failed.process();
        failed.fail("declined");
        refunds.save(failed);

        RefundFactsService service = new RefundFactsService(refunds, paymentAttempts);
        List<RefundFactResponse> facts = service.confirmedFacts();

        assertThat(facts).hasSize(1);
        RefundFactResponse fact = facts.get(0);
        assertThat(fact.refundNo()).isEqualTo(succeeded.getRefundNo());
        // Feature 016（FR-017 / N4）：优先取退款渠道尝试记录的真实渠道退款流水号；
        // 存量退款（无尝试记录）回退 refund-{id} 合成引用
        assertThat(fact.channelReference()).isEqualTo("refund-" + succeeded.getId());
        assertThat(fact.amountMinor()).isEqualTo(1000L);
        assertThat(fact.currencyCode()).isEqualTo("CNY");
        assertThat(fact.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void channelReferencePrefersRefundAttemptRecord() {
        Refund succeeded = new Refund("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
        succeeded.process();
        succeeded.succeed();
        refunds.save(succeeded);

        // 退款渠道尝试记录（Feature 016 / FR-017 ②）：channel_reference = 渠道退款流水号
        PaymentAttempt refundAttempt = PaymentAttempt.refundAttempt("PM-1", "mock");
        refundAttempt.accept("mock-refund-ref-real");
        refundAttempt.succeed();
        paymentAttempts.save(refundAttempt);

        RefundFactsService service = new RefundFactsService(refunds, paymentAttempts);
        List<RefundFactResponse> facts = service.confirmedFacts();

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).channelReference()).isEqualTo("mock-refund-ref-real");
    }
}
