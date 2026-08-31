package com.payment.catalog.api;

import com.payment.catalog.api.dto.CreateProductRequest;
import com.payment.catalog.api.dto.CreateSkuRequest;
import com.payment.catalog.api.dto.ProductResponse;
import com.payment.catalog.api.dto.SkuResponse;
import com.payment.catalog.application.CatalogApplicationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog 领域对外 REST 接口。
 */
@RestController
@RequestMapping
public class CatalogController {

    private final CatalogApplicationService catalogService;

    public CatalogController(CatalogApplicationService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@RequestBody CreateProductRequest request) {
        return ProductResponse.from(
                catalogService.createProduct(request.productCode(), request.name(), request.type()));
    }

    @PostMapping("/products/{id}/list")
    public ProductResponse listProduct(@PathVariable Long id) {
        return ProductResponse.from(catalogService.listProduct(id));
    }

    @PostMapping("/skus")
    @ResponseStatus(HttpStatus.CREATED)
    public SkuResponse createSku(@RequestBody CreateSkuRequest request) {
        return SkuResponse.from(
                catalogService.createSku(
                        request.skuCode(),
                        request.productId(),
                        request.name(),
                        request.priceMinor(),
                        request.currencyCode(),
                        request.deliveryDefinition()));
    }

    @PostMapping("/skus/{id}/activate")
    public SkuResponse activateSku(@PathVariable Long id) {
        return SkuResponse.from(catalogService.activateSku(id));
    }

    @PostMapping("/skus/{id}/suspend")
    public SkuResponse suspendSku(@PathVariable Long id) {
        return SkuResponse.from(catalogService.suspendSku(id));
    }

    @GetMapping("/skus/{id}")
    public SkuResponse getSku(@PathVariable Long id) {
        return SkuResponse.from(catalogService.getSku(id));
    }

    @GetMapping("/skus")
    public List<SkuResponse> listSkus() {
        return catalogService.listSkus().stream().map(SkuResponse::from).toList();
    }

    @GetMapping("/products/{id}")
    public ProductResponse getProduct(@PathVariable Long id) {
        return ProductResponse.from(catalogService.getProduct(id));
    }
}
