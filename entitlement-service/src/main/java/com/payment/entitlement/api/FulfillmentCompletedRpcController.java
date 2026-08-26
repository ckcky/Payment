package com.payment.entitlement.api;

import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.entitlement.application.EntitlementApplicationService;
import com.payment.entitlement.domain.Entitlement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 履约完成同步 RPC 入站端点（fulfillment-service → entitlement-service）。
 */
@RestController
public class FulfillmentCompletedRpcController {

    private final EntitlementApplicationService service;

    public FulfillmentCompletedRpcController(EntitlementApplicationService service) {
        this.service = service;
    }

    @PostMapping("/internal/entitlements/on-fulfillment-completed")
    public EntitlementGrantedResponse onFulfillmentCompleted(@RequestBody FulfillmentCompletedRequest request) {
        Entitlement e = service.grantOnFulfillmentCompleted(request);
        return new EntitlementGrantedResponse(e.getId(), e.getStatus().name());
    }
}
