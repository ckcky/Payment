package com.payment.order.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * catalog-service 的 Feign 客户端。目标地址由 {@code services.catalog.url} 配置（本地默认端口）。
 */
@FeignClient(name = "catalog-service", url = "${services.catalog.url:http://localhost:8082}")
public interface CatalogFeignClient {

    @GetMapping("/skus/{id}")
    CatalogSkuDto getSku(@PathVariable("id") Long id);
}
