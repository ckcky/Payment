package com.payment.refund.support;

import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.refund.application.EntitlementGateway;
import com.payment.refund.application.PaymentRefundGateway;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.infra.InMemoryRefundRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 退款服务测试栈：内存仓储 + 记录式 payment/entitlement RPC fake + 真实应用服务编排。
 */
public final class RefundTestStack {

    public final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    public final InMemoryIdempotencyRegistry registry = new InMemoryIdempotencyRegistry();
    public final RecordingPaymentRefundGateway payment = new RecordingPaymentRefundGateway();
    public final RecordingEntitlementGateway entitlement = new RecordingEntitlementGateway();

    public RefundApplicationService appService() {
        return new RefundApplicationService(refunds, payment, entitlement, registry);
    }

    /** 记录 attemptRefund 调用，返回可配置的退款尝试结果。 */
    public static final class RecordingPaymentRefundGateway implements PaymentRefundGateway {

        public PaymentAmountQueryResponse amount =
                new PaymentAmountQueryResponse(1L, "order-1", "user-1", 1000L, "CNY", "SUCCEEDED");
        public String attemptStatus = "SUCCEEDED";
        public final List<RefundAttemptRequest> attemptRequests = new ArrayList<>();

        @Override
        public PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request) {
            return amount;
        }

        @Override
        public RefundAttemptResponse attemptRefund(RefundAttemptRequest request) {
            attemptRequests.add(request);
            return new RefundAttemptResponse(request.refundId(), attemptStatus, "mock-refund-ref");
        }
    }

    /** 记录 notifyRefundPostProcess 调用并返回固定后处理响应。 */
    public static final class RecordingEntitlementGateway implements EntitlementGateway {

        public final List<RefundPostProcessRequest> postProcessRequests = new ArrayList<>();

        @Override
        public RefundPostProcessResponse notifyRefundPostProcess(RefundPostProcessRequest request) {
            postProcessRequests.add(request);
            return new RefundPostProcessResponse(request.refundId(), "REVOKED");
        }
    }
}
