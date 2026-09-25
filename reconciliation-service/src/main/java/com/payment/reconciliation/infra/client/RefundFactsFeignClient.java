package com.payment.reconciliation.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * reconciliation-service → payment-service 的 Feign 适配器（已确认退款事实查询）。
 *
 * <p>Feature 015/P3：退款域并入 payment-service（ADR-0064），REFUND 事实端点
 * {@code /internal/payments/refunds/confirmed-facts} 随退款域迁至 8084；
 * spec 038 起退款降为 payment 域内操作切片（原 {@code com.payment.refund} 顶层包已消灭），
 * 端点前缀由旧退款专用前缀（{@code /internal/} + {@code refunds/**}）收口为
 * {@code /internal/payments/refunds/**}。
 * 原服务名 refund-service 已从 Nacos 注册表退役。</p>
 *
 * <p>032/H-032-1：{@code period} 可选查询参数（缺省 = 全量，兼容窗口）。</p>
 */
@FeignClient(name = "payment-service", contextId = "refundFactsClient",
        configuration = FactsClientConfig.class)
public interface RefundFactsFeignClient {

    @GetMapping("/internal/payments/refunds/confirmed-facts")
    List<RefundFactDto> fetchConfirmedFacts(@RequestParam(value = "period", required = false) String period);
}
