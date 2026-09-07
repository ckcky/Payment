package com.payment.order.infra.client;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.order.application.FulfillmentGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * fulfillment-service 的 Feign 客户端（Feature 016 / ADR-0054）：
 * 支付成功后由 order 层驱动履约（下游按 orderId/paymentNo 幂等吸收重复通知）。
 */
@FeignClient(name = "fulfillment-service")
public interface FulfillmentFeignClient extends FulfillmentGateway {

    @PostMapping("/internal/fulfillments/on-payment-succeeded")
    @Override
    FulfillmentAcceptedResponse notifyPaymentSucceeded(@RequestBody PaymentSucceededRequest request);

    /** spec 019 / ADR-0067：退款收口 → 履约终止（下游按 item 撤全部 PENDING）。 */
    @PostMapping("/internal/fulfillments/on-refund")
    @Override
    RefundFulfillmentResponse onRefund(@RequestBody RefundFulfillmentRequest request);
}
