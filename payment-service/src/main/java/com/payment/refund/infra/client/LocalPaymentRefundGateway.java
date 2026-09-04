package com.payment.refund.infra.client;

import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.application.PaymentRefundService;
import com.payment.refund.application.PaymentRefundGateway;
import org.springframework.stereotype.Component;

/**
 * refund 域 → payment 域的支付能力端口（Feature 015 / P3）：
 * 退款服务并入 payment-service 后，原 Feign HTTP 自调用改为进程内直调，
 * 端口语义不变（查金额事实 / 渠道退款尝试），边界仍由 {@link PaymentRefundGateway} 表达。
 */
@Component
public class LocalPaymentRefundGateway implements PaymentRefundGateway {

    private final PaymentRefundService refundService;

    public LocalPaymentRefundGateway(PaymentRefundService refundService) {
        this.refundService = refundService;
    }

    @Override
    public PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request) {
        return refundService.queryAmount(request);
    }

    @Override
    public RefundAttemptResponse attemptRefund(RefundAttemptRequest request) {
        return refundService.refund(request);
    }
}
