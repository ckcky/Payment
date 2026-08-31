package com.payment.catalog.api;

import com.payment.catalog.api.dto.StockConfirmRequest;
import com.payment.catalog.api.dto.StockReleaseRequest;
import com.payment.catalog.api.dto.StockReserveRequest;
import com.payment.catalog.api.dto.StockSeedRequest;
import com.payment.catalog.application.StockApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存内部 REST 接口（order-service → catalog-service 同步 RPC）。
 *
 * <p>异常由 common-core 的 {@code GlobalExceptionHandler} 统一映射为 HTTP 状态：
 * 库存不足/冲突 → 409，SKU 不存在 → 404。调用方据此判定下单是否可继续。</p>
 */
@RestController
@RequestMapping("/internal/stock")
public class StockController {

    private final StockApplicationService stockService;

    public StockController(StockApplicationService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/seed")
    public void seed(@RequestBody StockSeedRequest request) {
        stockService.seedStock(request.skuId(), request.total());
    }

    @PostMapping("/reserve")
    public void reserve(@RequestBody StockReserveRequest request) {
        stockService.reserve(request.reservationId(), request.skuId(), request.quantity());
    }

    @PostMapping("/confirm")
    public void confirm(@RequestBody StockConfirmRequest request) {
        stockService.confirm(request.reservationId(), request.skuId(), request.quantity(), request.deductId());
    }

    @PostMapping("/release")
    public void release(@RequestBody StockReleaseRequest request) {
        stockService.release(request.reservationId(), request.skuId(), request.quantity());
    }
}
