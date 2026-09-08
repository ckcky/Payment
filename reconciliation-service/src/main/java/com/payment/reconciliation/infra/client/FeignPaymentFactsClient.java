package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.domain.PlatformFact;
import feign.RetryableException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link PaymentFactsClient} 的 Feign 实现：拉取支付事实 DTO 并映射为平台事实（type=PAYMENT）。
 *
 * <p>重试耗尽后 Feign 抛出 {@link RetryableException}（见 {@link FactsClientConfig#errorDecoder}），
 * 在此归一化为 {@code INTERNAL_ERROR}——对账上游只看到「读失败」，不感知重试与状态码细节。</p>
 */
@Component
public class FeignPaymentFactsClient implements PaymentFactsClient {

    private final PaymentFactsFeignClient feign;

    public FeignPaymentFactsClient(PaymentFactsFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public List<PlatformFact> fetchConfirmedFacts() {
        List<PaymentFactDto> dtos;
        try {
            dtos = feign.fetchConfirmedFacts();
        } catch (RetryableException ex) {
            throw BizException.of(ErrorCodes.INTERNAL_ERROR, "payment facts read failed after retries");
        }
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(d -> new PlatformFact(d.channelReference(), "PAYMENT",
                        d.amountMinor(), d.currencyCode(), d.status()))
                .toList();
    }
}
