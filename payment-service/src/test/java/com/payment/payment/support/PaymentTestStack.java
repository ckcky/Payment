package com.payment.payment.support;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.FulfillmentGateway;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentCallbackService;
import com.payment.payment.application.PaymentResultProcessor;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付服务测试栈：内存仓储 + 记录式履约 RPC fake + 真实应用服务编排。
 */
public final class PaymentTestStack {

    public final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    public final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    public final RecordingFulfillmentGateway fulfillment = new RecordingFulfillmentGateway();

    public final PaymentResultProcessor processor =
            new PaymentResultProcessor(payments, attempts, fulfillment);
    public final PaymentUnknownResolutionService resolution =
            new PaymentUnknownResolutionService(payments, processor, new NoopBusinessMetrics(),
                    new StructuredAuditLogger());
    public final PaymentCallbackService callback =
            new PaymentCallbackService(processor, payments, new NoopBusinessMetrics(),
                    new StructuredAuditLogger());

    public PaymentApplicationService appService(PaymentChannel channel) {
        return new PaymentApplicationService(payments, attempts, channel, fulfillment,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
    }

    public CreatePaymentCommand command(String idempotencyKey) {
        return new CreatePaymentCommand("txn-1", "order-1", "user-1", 100, "CNY", idempotencyKey, "mock");
    }

    /** 记录 notifyPaymentSucceeded 调用并返回固定受理响应，供测试断言。 */
    public static final class RecordingFulfillmentGateway implements FulfillmentGateway {

        public final List<PaymentSucceededRequest> succeededRequests = new ArrayList<>();

        @Override
        public FulfillmentAcceptedResponse notifyPaymentSucceeded(PaymentSucceededRequest request) {
            succeededRequests.add(request);
            return new FulfillmentAcceptedResponse(1L, "PROCESSING");
        }
    }
}
