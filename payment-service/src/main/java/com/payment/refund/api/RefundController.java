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

    // ADR-0063：查询与收敛端点一律用业务单号 refundNo 寻址，数值主键不出服务边界。
    @GetMapping("/{refundNo}")
    public RefundResponse getRefund(@PathVariable String refundNo) {
        return RefundResponse.from(applicationService.getRefund(refundNo));
    }

    @PostMapping("/{refundNo}/resolve")
    public RefundResponse resolveRefund(@PathVariable String refundNo, @RequestBody ResolveRefundRequest request) {
        return RefundResponse.from(callbackService.resolveRefund(refundNo, request.status()));
    }
}
