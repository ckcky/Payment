package com.payment.order.infra.client;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.order.application.PaymentGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * payment-service 的 Feign 客户端：创建支付意图。
 */
@FeignClient(name = "payment-service", url = "${services.payment.url:http://localhost:8084}")
public interface PaymentFeignClient extends PaymentGateway {

    @PostMapping("/payments")
    @Override
    CreatePaymentResponse createPayment(@RequestBody CreatePaymentRequest request);
}
