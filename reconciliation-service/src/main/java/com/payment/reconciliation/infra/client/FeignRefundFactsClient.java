package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.PlatformFact;
import feign.RetryableException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link RefundFactsClient} 的 Feign 实现：拉取退款事实 DTO 并映射为平台事实（type=REFUND）。
 *
 * <p>重试耗尽后归一化为 {@code INTERNAL_ERROR}（同 {@link FeignPaymentFactsClient}）。</p>
 */
@Component
public class FeignRefundFactsClient implements RefundFactsClient {

    private final RefundFactsFeignClient feign;

    public FeignRefundFactsClient(RefundFactsFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public List<PlatformFact> fetchConfirmedFacts() {
        List<RefundFactDto> dtos;
        try {
            dtos = feign.fetchConfirmedFacts();
        } catch (RetryableException ex) {
            throw BizException.of(ErrorCodes.INTERNAL_ERROR, "refund facts read failed after retries");
        }
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(d -> new PlatformFact(d.channelReference(), "REFUND",
                        d.amountMinor(), d.currencyCode(), d.status()))
                .toList();
    }
}
