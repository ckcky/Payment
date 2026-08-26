package com.payment.order.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.application.CatalogClient;
import com.payment.order.application.SkuSnapshot;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogClient} 的 Feign 实现：调用 catalog-service 并映射为 SKU 快照。
 */
@Component
public class FeignCatalogClient implements CatalogClient {

    private final CatalogFeignClient feign;

    public FeignCatalogClient(CatalogFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public SkuSnapshot getSku(Long skuId) {
        try {
            return feign.getSku(skuId).toSnapshot();
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "sku not found: " + skuId);
            }
            throw e;
        }
    }
}
