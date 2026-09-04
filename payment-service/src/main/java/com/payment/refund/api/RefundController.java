package com.payment.refund.api;

import com.payment.refund.application.CreateRefundCommand;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundRpcCallbackService;
import com.payment.refund.domain.Refund;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款领域对外 REST 接口（内部同步 RPC 面）。
 */
@RestController
@RequestMapping("/internal/refunds")
public class RefundController {

    private final RefundApplicationService applicationService;
    private final RefundRpcCallbackService callbackService;

    public RefundController(RefundApplicationService applicationService,
                            RefundRpcCallbackService callbackService) {
        this.applicationService = applicationService;
        this.callbackService = callbackService;
    }

    @PostMapping
    public RefundResponse createRefund(@RequestBody CreateRefundCommand command) {
        Refund refund = applicationService.createRefund(command);
        return RefundResponse.from(refund);
    }

    @GetMapping("/{id}")
    public RefundResponse getRefund(@PathVariable Long id) {
        return RefundResponse.from(applicationService.getRefund(id));
    }

    @PostMapping("/{id}/resolve")
    public RefundResponse resolveRefund(@PathVariable Long id, @RequestBody ResolveRefundRequest request) {
        return RefundResponse.from(callbackService.resolveRefund(id, request.status()));
    }
}
