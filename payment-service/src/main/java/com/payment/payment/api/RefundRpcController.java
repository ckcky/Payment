package com.payment.payment.api;

import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.application.PaymentRefundService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面向 refund-service 的内部 RPC 接口（金额查询 + 退款尝试）。
 */
@RestController
public class RefundRpcController {

    private final PaymentRefundService refundService;

    public RefundRpcController(PaymentRefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/internal/payments/query-amount")
    public PaymentAmountQueryResponse queryAmount(@RequestBody PaymentAmountQueryRequest request) {
        return refundService.queryAmount(request);
    }

    @PostMapping("/internal/payments/refund-attempt")
    public RefundAttemptResponse refundAttempt(@RequestBody RefundAttemptRequest request) {
        return refundService.refund(request);
    }
}
