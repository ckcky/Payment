package com.payment.payment.application.refund;

import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.payment.domain.Payment;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.infra.persistence.InMemoryChannelOrderRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import com.payment.payment.api.dto.RefundFactResponse;
import com.payment.payment.domain.Refund;
import com.payment.payment.infra.InMemoryRefundRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款事实抽取测试（US3 对账）：仅返回已确认成功的退款事实，外部引用为 {@code refund-{id}}。
 *
 * <p>spec 032：补 merchantId 反查 + C-22 修复（渠道引用取成功尝试，不取首条）。</p>
 */
class RefundFactsServiceTest {

    private final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    private final InMemoryChannelOrderRepository paymentAttempts = new InMemoryChannelOrderRepository();
    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();

    private RefundFactsService service() {
        return new RefundFactsService(refunds, paymentAttempts, payments);
    }

    private Payment payment(String paymentNo, String merchantId) {
        Payment p = Payment.rehydrate(1L, paymentNo, "tx-1", "order-1", "user-1", 1000L, "CNY",
                "idem-" + paymentNo, com.payment.payment.domain.PaymentStatus.SUCCEEDED,
                null, null, 0, null, 1, 1, merchantId);
        payments.save(p);
        return p;
    }

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

        List<RefundFactResponse> facts = service().confirmedFacts();

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
        ChannelOrder refundAttempt = ChannelOrder.refundAttempt("PM-1", "mock", 1000L, "CNY");
        refundAttempt.accept("mock-refund-ref-real");
        refundAttempt.succeed();
        paymentAttempts.save(refundAttempt);

        List<RefundFactResponse> facts = service().confirmedFacts();

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).channelReference()).isEqualTo("mock-refund-ref-real");
    }

    /** spec 032 / C-22（H19）：先失败后成功的双尝试 ⇒ reference 指向成功尝试流水。 */
    @Test
    void channelReferenceSkipsFailedAttemptsAndPicksSucceededOne() {
        Refund succeeded = new Refund("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
        succeeded.process();
        succeeded.succeed();
        refunds.save(succeeded);

        ChannelOrder failedAttempt = ChannelOrder.refundAttempt("PM-1", "mock", 1000L, "CNY");
        failedAttempt.accept("mock-refund-ref-FAILED");
        failedAttempt.fail("channel declined");
        paymentAttempts.save(failedAttempt);

        ChannelOrder okAttempt = ChannelOrder.refundAttempt("PM-1", "mock", 1000L, "CNY");
        okAttempt.accept("mock-refund-ref-OK");
        okAttempt.succeed();
        paymentAttempts.save(okAttempt);

        List<RefundFactResponse> facts = service().confirmedFacts();

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).channelReference()).isEqualTo("mock-refund-ref-OK");
    }

    /** spec 032 / H-032-1：merchantId 经 payment 反查；反查不到为 null（不猜）。 */
    @Test
    void merchantIdResolvedFromPayment() {
        payment("PM-1", "42");
        Refund succeeded = new Refund("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
        succeeded.process();
        succeeded.succeed();
        refunds.save(succeeded);

        Refund orphan = new Refund("order-2", "PM-MISSING", "user-1", 500L, "CNY", "customer",
                "idem-2", List.of());
        orphan.process();
        orphan.succeed();
        refunds.save(orphan);

        List<RefundFactResponse> facts = service().confirmedFacts();

        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(RefundFactResponse::merchantId)
                .containsExactlyInAnyOrder("42", null);
    }
}
