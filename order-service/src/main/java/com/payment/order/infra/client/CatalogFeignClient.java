package com.payment.order.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import com.payment.order.application.ConfirmStockCommand;
import com.payment.order.application.ReleaseStockCommand;
import com.payment.order.application.ReserveStockCommand;

/**
 * catalog-service 的 Feign 客户端。目标地址由 {@code services.catalog.url} 配置（本地默认端口）。
 */
@FeignClient(name = "catalog-service")
public interface CatalogFeignClient {

    @GetMapping("/skus/{id}")
    CatalogSkuDto getSku(@PathVariable("id") Long id);

    @PostMapping("/internal/stock/reserve")
    void reserveStock(@RequestBody ReserveStockCommand request);

    @PostMapping("/internal/stock/confirm")
    void confirmStock(@RequestBody ConfirmStockCommand request);

    @PostMapping("/internal/stock/release")
    void releaseStock(@RequestBody ReleaseStockCommand request);

    @PostMapping("/internal/stock/seckill/deduct")
    SeckillDeductResponse seckillDeduct(@RequestParam("skuId") Long skuId, @RequestParam("quantity") long quantity);

    @PostMapping("/internal/stock/seckill/rollback")
    void seckillRollback(@RequestParam("skuId") Long skuId, @RequestParam("quantity") long quantity);
}
