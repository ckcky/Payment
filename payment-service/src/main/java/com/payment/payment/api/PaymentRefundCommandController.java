package com.payment.payment.api;

import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.payment.application.PaymentAutoRefundService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动退款命令入口（Feature 016 / ADR-0054）：order transaction 层判定 surplus 后，
 * 以 {@code transactionNo + paymentNo} 调用本端点发起自动退款；payment 仅作执行方。
 */
@RestController
@RequestMapping("/internal/payments")
public class PaymentRefundCommandController {

    private final PaymentAutoRefundService autoRefundService;

    public PaymentRefundCommandController(PaymentAutoRefundService autoRefundService) {
        this.autoRefundService = autoRefundService;
    }

    @PostMapping("/refund-command")
    public RefundCommandResponse refundCommand(@RequestBody RefundCommandRequest request) {
        return autoRefundService.refundByOrder(request);
    }
}
