package com.payment.fulfillment.infra.client;

import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.fulfillment.application.EntitlementGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "entitlement-service")
public interface EntitlementFeignClient extends EntitlementGateway {

    @PostMapping("/internal/entitlements/on-fulfillment-completed")
    @Override
    EntitlementGrantedResponse notifyFulfillmentCompleted(@RequestBody FulfillmentCompletedRequest request);

    /** 退款撤销（spec 019 / ADR-0067）：fulfillment 退款链下游触发，entitlement 侧幂等。 */
    @PostMapping("/internal/entitlements/on-refund")
    @Override
    RefundPostProcessResponse revokeOnRefund(@RequestBody RefundPostProcessRequest request);
}
