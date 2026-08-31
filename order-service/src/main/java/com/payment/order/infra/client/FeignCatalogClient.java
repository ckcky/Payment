package com.payment.order.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.application.CatalogClient;
import com.payment.order.application.ConfirmStockCommand;
import com.payment.order.application.ReleaseStockCommand;
import com.payment.order.application.ReserveStockCommand;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.application.SeckillResult;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogClient} 的 Feign 实现：调用 catalog-service 并映射为领域视图/命令。
 * catalog 侧异常（库存不足/冲突 → 409，SKU 不存在 → 404）统一映射为领域异常。
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

    @Override
    public void reserveStock(ReserveStockCommand request) {
        try {
            feign.reserveStock(request);
        } catch (FeignException e) {
            throw mapError(e, "reserve stock failed sku=" + request.skuId());
        }
    }

    @Override
    public void confirmStock(ConfirmStockCommand request) {
        try {
            feign.confirmStock(request);
        } catch (FeignException e) {
            throw mapError(e, "confirm stock failed sku=" + request.skuId());
        }
    }

    @Override
    public void releaseStock(ReleaseStockCommand request) {
        try {
            feign.releaseStock(request);
        } catch (FeignException e) {
            throw mapError(e, "release stock failed sku=" + request.skuId());
        }
    }

    @Override
    public SeckillResult trySeckillDeduct(Long skuId, long quantity) {
        try {
            SeckillDeductResponse r = feign.seckillDeduct(skuId, quantity);
            return new SeckillResult(true, r.remaining());
        } catch (FeignException e) {
            if (e.status() == 409) {
                return new SeckillResult(false, -1);
            }
            throw e;
        }
    }

    @Override
    public void rollbackSeckill(Long skuId, long quantity) {
        try {
            feign.seckillRollback(skuId, quantity);
        } catch (FeignException e) {
            throw mapError(e, "seckill rollback failed sku=" + skuId);
        }
    }

    private BizException mapError(FeignException e, String message) {
        if (e.status() == 404) {
            return BizException.of(ErrorCodes.NOT_FOUND, message);
        }
        if (e.status() == 409) {
            return BizException.of(ErrorCodes.CONFLICT, message);
        }
        return BizException.of(ErrorCodes.INTERNAL_ERROR, message);
    }
}
