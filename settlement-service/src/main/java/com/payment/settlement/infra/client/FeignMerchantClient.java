package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.settlement.application.MerchantClient;
import com.payment.settlement.application.MerchantView;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * MerchantClient 的 Feign 适配器：把 merchant-service DTO 映射为领域视图，404 转 NOT_FOUND。
 */
@Component
public class FeignMerchantClient implements MerchantClient {

    private final MerchantFeignClient feign;

    public FeignMerchantClient(MerchantFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public MerchantView getMerchant(Long merchantId) {
        try {
            MerchantDto dto = feign.getMerchant(merchantId);
            return new MerchantView(dto.id(), dto.status(), dto.settlementEligible());
        } catch (FeignException.NotFound e) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "merchant not found: " + merchantId);
        }
    }
}
