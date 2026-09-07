package com.payment.refund.support;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.payment.application.OrderGateway;
import com.payment.payment.domain.Payment;
import com.payment.refund.application.LedgerPostingGateway;
import com.payment.refund.application.PaymentRefundGateway;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundResultProcessor;
import com.payment.refund.infra.InMemoryRefundRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 退款服务测试栈（spec 019 T108 重构）：内存仓储 + 记录式 payment/ledger/order RPC fake
 * + 真实应用服务与 {@link RefundResultProcessor} 三路收敛编排。
 *
 * <p>spec 019 变更：原 fulfillment/entitlement 直调扇出与后处理编排器（RefundPostProcessOrchestrator）
 * 已删除——业务下游扇出移交 order 侧收口（ADR-0067），本栈以 {@link RecordingOrderGateway}
 * 验证「退款终态 → 通知 order（TXRF+PMRF 双号）」。</p>
 */
public final class RefundTestStack {

    public final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
    public final RecordingPaymentRefundGateway payment = new RecordingPaymentRefundGateway();
    public final RecordingLedgerGateway ledger = new RecordingLedgerGateway();
    public final RecordingOrderGateway order = new RecordingOrderGateway();

    public RefundResultProcessor resultProcessor() {
        return new RefundResultProcessor(refunds, order, ledger,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    public RefundApplicationService appService() {
        return new RefundApplicationService(refunds, payment, resultProcessor(),
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

    /** 记录 postRefundCapture 调用（不真正触达账本）。 */
    public static final class RecordingLedgerGateway implements LedgerPostingGateway {

        public final List<String> postingKeys = new ArrayList<>();

        @Override
        public void postRefundCapture(String idempotencyKey, String refundNo, long amountMinor, String currencyCode) {
            postingKeys.add(idempotencyKey + ":" + refundNo + ":" + amountMinor);
        }
    }

    /** 记录 order 通知调用（支付成功 + 退款终态，TXRF+PMRF 双号）。 */
    public static final class RecordingOrderGateway implements OrderGateway {

        public final List<RefundResultNotification> refundNotifications = new ArrayList<>();
        public int paymentSucceededCalls;
        /** 置为 true 模拟通知 order RPC 抛错（验证不因通知失败回滚退款终态事实）。 */
        public boolean failRefundNotify = false;

        @Override
        public void notifyPaymentSucceeded(com.payment.common.dto.rpc.PaymentSucceededRequest request) {
            paymentSucceededCalls++;
        }

        @Override
        public void notifyRefundResult(RefundResultNotification notification) {
            if (failRefundNotify) {
                throw new IllegalStateException("order notify RPC failed");
            }
            refundNotifications.add(notification);
        }
    }

    /** 便捷：构造一笔成功支付事实（供 RefundFactsService 等测试使用）。 */
    public static Payment payment(String paymentNo) {
        Payment payment = new Payment("TXN-" + paymentNo, "order-1", "user-1", 1000L, "CNY", "idem-" + paymentNo);
        payment.start(1L);
        payment.succeed();
        return payment;
    }
}
