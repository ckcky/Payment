package com.payment.order.infra.client;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.order.application.PaymentGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * payment-service 的 Feign 客户端：创建支付意图。
 */
@FeignClient(name = "payment-service")
public interface PaymentFeignClient extends PaymentGateway {

    @PostMapping("/payments")
    @Override
    CreatePaymentResponse createPayment(@RequestBody CreatePaymentRequest request);

    /** Feature 016（ADR-0054）：surplus 自动退款命令执行入口。 */
    @PostMapping("/internal/payments/refund-command")
    @Override
    RefundCommandResponse refund(@RequestBody RefundCommandRequest request);
}
