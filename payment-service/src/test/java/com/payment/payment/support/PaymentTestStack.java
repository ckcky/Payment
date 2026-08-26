package com.payment.payment.support;

import com.payment.common.core.event.DomainEvent;
import com.payment.common.core.idempotency.InMemoryIdempotencyRegistry;
import com.payment.payment.application.CreatePaymentCommand;
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
 * 支付服务测试栈：内存仓储 + 捕获事件的发布器 + 真实应用服务编排。
 */
public final class PaymentTestStack {

    public final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    public final InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
    public final InMemoryIdempotencyRegistry registry = new InMemoryIdempotencyRegistry();
    public final List<DomainEvent> events = new ArrayList<>();

    public final PaymentResultProcessor processor =
            new PaymentResultProcessor(payments, attempts, events::add);
    public final PaymentUnknownResolutionService resolution =
            new PaymentUnknownResolutionService(payments, processor);
    public final PaymentCallbackService callback =
            new PaymentCallbackService(processor);

    public PaymentApplicationService appService(PaymentChannel channel) {
        return new PaymentApplicationService(payments, attempts, channel, registry, events::add);
    }

    public CreatePaymentCommand command(String idempotencyKey) {
        return new CreatePaymentCommand("txn-1", "order-1", "user-1", 100, "CNY", idempotencyKey, "mock");
    }
}
