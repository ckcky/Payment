package com.payment.payment.infra.client;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.OrderGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * order-service 的 Feign 客户端：支付成功回写订单/交易状态。
 * Feature 016（ADR-0054）：order 不再返回 409——surplus 判定与自动退款发起归 order transaction 层，
 * 本客户端直接作为 {@code OrderGateway} 注入。
 */
@FeignClient(name = "order-service")
public interface OrderFeignClient extends OrderGateway {

    @PostMapping("/internal/orders/on-payment-succeeded")
    @Override
    void notifyPaymentSucceeded(@RequestBody PaymentSucceededRequest request);
}
