package com.payment.payment.infra.client;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.FulfillmentGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "fulfillment-service", primary = false)
public interface FulfillmentFeignClient extends FulfillmentGateway {

    @PostMapping("/internal/fulfillments/on-payment-succeeded")
    @Override
    FulfillmentAcceptedResponse notifyPaymentSucceeded(@RequestBody PaymentSucceededRequest request);
}
