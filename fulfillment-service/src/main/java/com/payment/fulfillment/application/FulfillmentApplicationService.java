package com.payment.fulfillment.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 履约应用服务：接收 payment-service 的同步 RPC，创建幂等履约任务；
 * 履约完成后通过同步 RPC（{@link EntitlementGateway}）触发权益授予。
 *
 * <p>「支付成功」只触发履约，不决定最终履约状态；重复请求（同 paymentId）不产生第二条履约。</p>
 */
@Service
public class FulfillmentApplicationService {

    private static final String MODULE = "fulfillment";

    private final FulfillmentRepository repository;
    private final EntitlementGateway entitlementGateway;
    private final BusinessMetrics metrics;

    public FulfillmentApplicationService(FulfillmentRepository repository,
                                         EntitlementGateway entitlementGateway,
                                         BusinessMetrics metrics) {
        this.repository = repository;
        this.entitlementGateway = entitlementGateway;
        this.metrics = metrics;
    }

    public Fulfillment acceptPaymentSucceeded(PaymentSucceededRequest request) {
        String sourcePaymentId = String.valueOf(request.paymentId());

        // 幂等：同一 sourcePaymentId 只创建一条履约。
        Optional<Fulfillment> existing = repository.findBySourcePaymentId(sourcePaymentId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Fulfillment fulfillment = newFulfillment(request.orderId(), sourcePaymentId);
        fulfillment.start();

        // 同步 mock 处理（PROCESSING → DELIVERED）。真实实现会在此处调用交付渠道；
        // 未知结果绝不臆断为成功——异常时记录失败，不触发权益、不回写支付事实。
        try {
            fulfillment.deliver();
        } catch (RuntimeException ex) {
            fulfillment.fail(ex.getMessage());
            metrics.counter("fulfillment.failed", 1.0, "module", MODULE);
            return repository.save(fulfillment);
        }

        metrics.counter("fulfillment.completed", 1.0, "module", MODULE);

        Fulfillment saved = repository.save(fulfillment);

        // 履约完成后触发权益授予（同步 RPC）；权益失败不反写履约成功事实（按 plan 语义，履约已 DELIVERED）。
        entitlementGateway.notifyFulfillmentCompleted(
                new FulfillmentCompletedRequest(saved.getId(), saved.getOrderId(), request.userId()));
        return saved;
    }

    /** 测试缝隙：供单测注入可失败的 mock 交付（不改动状态机）。 */
    Fulfillment newFulfillment(String orderId, String sourcePaymentId) {
        return new Fulfillment(orderId, null, "mock delivery", sourcePaymentId);
    }
}
