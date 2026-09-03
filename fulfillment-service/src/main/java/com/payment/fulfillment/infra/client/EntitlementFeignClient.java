package com.payment.fulfillment.infra.client;

import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.fulfillment.application.EntitlementGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "entitlement-service")
public interface EntitlementFeignClient extends EntitlementGateway {

    @PostMapping("/internal/entitlements/on-fulfillment-completed")
    @Override
    EntitlementGrantedResponse notifyFulfillmentCompleted(@RequestBody FulfillmentCompletedRequest request);
}
