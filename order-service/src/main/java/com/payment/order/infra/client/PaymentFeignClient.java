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
 *
 * <p>绑定 {@link PaymentFeignConfig}：保留下游 4xx 的业务语义（如 409 CHANNEL_UNAVAILABLE），
 * 避免被兜底成 500——路由失败是**可预期的业务结果**，不是服务端故障。</p>
 */
@FeignClient(name = "payment-service", configuration = PaymentFeignConfig.class)
public interface PaymentFeignClient extends PaymentGateway {

    /** spec 041：支付创建端点统一为 {@code POST /payments/pay}。 */
    @PostMapping("/payments/pay")
    @Override
    CreatePaymentResponse createPayment(@RequestBody CreatePaymentRequest request);

    /** Feature 016（ADR-0054）：surplus 自动退款命令执行入口。 */
    @PostMapping("/internal/payments/refund-command")
    @Override
    RefundCommandResponse refund(@RequestBody RefundCommandRequest request);
}
