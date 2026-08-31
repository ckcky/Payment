package com.payment.catalog.web;

import com.payment.catalog.application.seckill.SeckillResult;
import com.payment.catalog.application.seckill.SeckillStockService;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀配额预扣 REST 端点（014，内部端点）。订单服务下单前调用 {@code /deduct} 做原子准入，
 * 失败时 {@code /rollback} 回补。{@code /seed} 用于演示播种配额。
 */
@RestController
@RequestMapping("/internal/stock/seckill")
public class SeckillStockController {

    private final SeckillStockService service;

    public SeckillStockController(SeckillStockService service) {
        this.service = service;
    }

    /** 秒杀预扣响应：扣减后剩余；bypassed=true 表示该 SKU 未播种秒杀配额（普通品放行）。 */
    public record SeckillDeductResponse(long remaining, boolean bypassed) {
    }

    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.OK)
    public void seed(@RequestParam Long skuId, @RequestParam long total) {
        service.seed(skuId, total);
    }

    @PostMapping("/deduct")
    public SeckillDeductResponse deduct(@RequestParam Long skuId, @RequestParam long quantity) {
        SeckillResult r = service.tryPreDeduct(skuId, quantity);
        if (!r.allowed()) {
            throw BizException.of(ErrorCodes.CONFLICT, "seckill stock insufficient sku=" + skuId);
        }
        return new SeckillDeductResponse(r.remaining(), r.bypassed());
    }

    @PostMapping("/rollback")
    @ResponseStatus(HttpStatus.OK)
    public void rollback(@RequestParam Long skuId, @RequestParam long quantity) {
        service.rollback(skuId, quantity);
    }
}
