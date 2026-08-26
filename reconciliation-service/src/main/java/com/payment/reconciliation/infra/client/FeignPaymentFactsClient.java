package com.payment.reconciliation.infra.client;

import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.domain.PlatformFact;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link PaymentFactsClient} 的 Feign 实现：拉取支付事实 DTO 并映射为平台事实（type=PAYMENT）。
 */
@Component
public class FeignPaymentFactsClient implements PaymentFactsClient {

    private final PaymentFactsFeignClient feign;

    public FeignPaymentFactsClient(PaymentFactsFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public List<PlatformFact> fetchConfirmedFacts() {
        List<PaymentFactDto> dtos = feign.fetchConfirmedFacts();
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(d -> new PlatformFact(d.channelReference(), "PAYMENT",
                        d.amountMinor(), d.currencyCode(), d.status()))
                .toList();
    }
}
