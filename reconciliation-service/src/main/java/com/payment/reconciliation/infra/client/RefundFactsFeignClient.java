package com.payment.reconciliation.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * reconciliation-service → payment-service 的 Feign 适配器（已确认退款事实查询）。
 *
 * <p>Feature 015/P3：退款域并入 payment-service（ADR-0064），REFUND 事实端点
 * {@code /internal/refunds/confirmed-facts} 随 {@code com.payment.refund} 包迁至 8084；
 * 原服务名 refund-service 已从 Nacos 注册表退役。</p>
 *
 * <p>032/H-032-1：{@code period} 可选查询参数（缺省 = 全量，兼容窗口）。</p>
 */
@FeignClient(name = "payment-service", contextId = "refundFactsClient",
        configuration = FactsClientConfig.class)
public interface RefundFactsFeignClient {

    @GetMapping("/internal/refunds/confirmed-facts")
    List<RefundFactDto> fetchConfirmedFacts(@RequestParam(value = "period", required = false) String period);
}
