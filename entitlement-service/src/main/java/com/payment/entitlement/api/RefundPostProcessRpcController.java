package com.payment.entitlement.api;

import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.entitlement.application.EntitlementApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款成功后的权益后处理 RPC 入站端点（refund-service → entitlement-service）。
 */
@RestController
public class RefundPostProcessRpcController {

    private final EntitlementApplicationService service;

    public RefundPostProcessRpcController(EntitlementApplicationService service) {
        this.service = service;
    }

    @PostMapping("/internal/entitlements/on-refund")
    public RefundPostProcessResponse onRefund(@RequestBody RefundPostProcessRequest request) {
        return service.revokeOnRefund(request);
    }
}
