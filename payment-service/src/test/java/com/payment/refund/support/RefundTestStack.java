package com.payment.refund.support;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.refund.application.EntitlementGateway;
import com.payment.refund.application.FulfillmentGateway;
import com.payment.refund.application.LedgerPostingGateway;
import com.payment.refund.application.PaymentRefundGateway;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundPostProcessOrchestrator;
import com.payment.refund.domain.RefundPostProcessAttempt;
import com.payment.refund.domain.RefundPostProcessAttemptRepository;
import com.payment.refund.infra.InMemoryRefundRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 退款服务测试栈：内存仓储 + 记录式 payment/entitlement/fulfillment/ledger RPC fake
 * + 真实应用服务与后处理编排。
 */
public final class RefundTestStack {

    public final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    public final RecordingPaymentRefundGateway payment = new RecordingPaymentRefundGateway();
    public final RecordingEntitlementGateway entitlement = new RecordingEntitlementGateway();
    public final RecordingFulfillmentGateway fulfillment = new RecordingFulfillmentGateway();
    public final RecordingLedgerGateway ledger = new RecordingLedgerGateway();
    public final InMemoryRefundPostProcessAttemptRepository attempts =
            new InMemoryRefundPostProcessAttemptRepository();

    public RefundApplicationService appService() {
        RefundPostProcessOrchestrator orchestrator = new RefundPostProcessOrchestrator(
                fulfillment, entitlement, ledger, attempts, new NoopBusinessMetrics(), new StructuredAuditLogger());
        return new RefundApplicationService(refunds, payment, orchestrator,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    /** 记录 attemptRefund 调用，返回可配置的退款尝试结果。 */
    public static final class RecordingPaymentRefundGateway implements PaymentRefundGateway {

        public PaymentAmountQueryResponse amount =
                new PaymentAmountQueryResponse("PM-1", "order-1", "user-1", 1000L, "CNY", "SUCCEEDED");
        public String attemptStatus = "SUCCEEDED";
        public final List<RefundAttemptRequest> attemptRequests = new ArrayList<>();

        @Override
        public PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request) {
            return amount;
        }

        @Override
        public RefundAttemptResponse attemptRefund(RefundAttemptRequest request) {
            attemptRequests.add(request);
            return new RefundAttemptResponse(request.refundNo(), attemptStatus, "mock-refund-ref");
        }
    }

    /** 记录 notifyRefundPostProcess 调用并返回固定后处理响应。 */
    public static final class RecordingEntitlementGateway implements EntitlementGateway {

        public final List<RefundPostProcessRequest> postProcessRequests = new ArrayList<>();
        /** 置为 true 模拟后处理 RPC 抛错（验证不因后处理失败回滚退款成功）。 */
        public boolean failPostProcess = false;

        @Override
        public RefundPostProcessResponse notifyRefundPostProcess(RefundPostProcessRequest request) {
            postProcessRequests.add(request);
            if (failPostProcess) {
                throw new IllegalStateException("post-process RPC failed");
            }
            return new RefundPostProcessResponse(request.refundNo(), "REVOKED");
        }
    }

    /** 记录 notifyRefund 调用并返回固定撤销响应（CANCELLED）。 */
    public static final class RecordingFulfillmentGateway implements FulfillmentGateway {

        public final List<RefundFulfillmentRequest> refundRequests = new ArrayList<>();

        @Override
        public RefundFulfillmentResponse notifyRefund(RefundFulfillmentRequest request) {
            refundRequests.add(request);
            return new RefundFulfillmentResponse(request.refundNo(), "CANCELLED");
        }
    }

    /** 记录 postRefundCapture 调用（不真正触达账本）。 */
    public static final class RecordingLedgerGateway implements LedgerPostingGateway {

        public final List<String> postingKeys = new ArrayList<>();

        @Override
        public void postRefundCapture(String idempotencyKey, String refundNo, long amountMinor, String currencyCode) {
            postingKeys.add(idempotencyKey + ":" + refundNo + ":" + amountMinor);
        }
    }

    /** 内存版后处理尝试仓储（仅测试用）。 */
    public static final class InMemoryRefundPostProcessAttemptRepository
            implements RefundPostProcessAttemptRepository {

        private final Map<String, List<RefundPostProcessAttempt>> byRefund = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(RefundPostProcessAttempt attempt) {
            if (attempt.getId() == null) {
                attempt.setId(idGen.incrementAndGet());
            }
            byRefund.computeIfAbsent(attempt.getRefundNo(), k -> new ArrayList<>()).add(attempt);
        }

        @Override
        public List<RefundPostProcessAttempt> findByRefundNo(String refundNo) {
            return byRefund.getOrDefault(refundNo, List.of());
        }
    }
}
