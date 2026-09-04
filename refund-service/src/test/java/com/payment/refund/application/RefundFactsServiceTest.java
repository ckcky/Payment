package com.payment.refund.application;

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

        RefundFactsService service = new RefundFactsService(refunds);
        List<RefundFactResponse> facts = service.confirmedFacts();

        assertThat(facts).hasSize(1);
        RefundFactResponse fact = facts.get(0);
        assertThat(fact.refundId()).isEqualTo(succeeded.getId());
        assertThat(fact.channelReference()).isEqualTo("refund-" + succeeded.getId());
        assertThat(fact.amountMinor()).isEqualTo(1000L);
        assertThat(fact.currencyCode()).isEqualTo("CNY");
        assertThat(fact.status()).isEqualTo("SUCCEEDED");
    }
}
