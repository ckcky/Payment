package com.payment.fulfillment.api;

import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.fulfillment.application.FulfillmentApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款 → 履约撤销的同步 RPC 面（ADR-0017）。
 */
@RestController
@RequestMapping("/internal/fulfillments")
public class FulfillmentRefundController {

    private final FulfillmentApplicationService applicationService;

    public FulfillmentRefundController(FulfillmentApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/on-refund")
    public RefundFulfillmentResponse onRefund(@RequestBody RefundFulfillmentRequest request) {
        return applicationService.onRefund(request);
    }
}
