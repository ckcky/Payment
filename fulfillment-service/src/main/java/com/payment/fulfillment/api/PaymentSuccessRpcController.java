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
        Fulfillment f = service.acceptPaymentSucceeded(request);
        return new FulfillmentAcceptedResponse(f.getId(), f.getStatus().name());
    }
}
