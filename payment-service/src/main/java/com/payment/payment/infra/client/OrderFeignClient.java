package com.payment.payment.infra.client;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.OrderGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * order-service 的 Feign 客户端：支付成功回写订单/交易状态。
 * primary=false：注入一律走 {@link FeignOrderGateway}（翻译 409 ORDER_NOT_PAYABLE，Feature 015 / C5）。
 */
@FeignClient(name = "order-service", primary = false)
public interface OrderFeignClient extends OrderGateway {

    @PostMapping("/internal/orders/on-payment-succeeded")
    @Override
    void notifyPaymentSucceeded(@RequestBody PaymentSucceededRequest request);
}
