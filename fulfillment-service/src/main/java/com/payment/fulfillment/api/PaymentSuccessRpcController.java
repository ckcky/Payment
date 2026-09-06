package com.payment.fulfillment.api;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.fulfillment.application.FulfillmentApplicationService;
import com.payment.fulfillment.domain.Fulfillment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * payment-service → fulfillment-service 的同步入站 RPC：支付成功触发履约（幂等）。
 */
@RestController
public class PaymentSuccessRpcController {

    private final FulfillmentApplicationService service;

    public PaymentSuccessRpcController(FulfillmentApplicationService service) {
        this.service = service;
    }

    @PostMapping("/internal/fulfillments/on-payment-succeeded")
    public FulfillmentAcceptedResponse onPaymentSucceeded(@RequestBody PaymentSucceededRequest request) {
        // spec 018：逐明细建履约（1 单 N 明细 = N 条履约）；响应保持既有契约形态，
        // 返回首条（幂等重复通知时为已存在明细），上游 order 侧不依赖逐条结果。
        java.util.List<Fulfillment> fulfillments = service.acceptPaymentSucceeded(request);
        Fulfillment first = fulfillments.get(0);
        return new FulfillmentAcceptedResponse(first.getId(), first.getStatus().name());
    }
}
