package com.payment.payment.infra.client;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.FulfillmentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幂等履约 RPC 的弹性装饰器：对可安全重试的「支付成功通知」（下游以 orderId/paymentId 幂等吸收重复）
 * 施加指数退避重试，降低瞬时网络抖动导致的履约丢失。熔断器由 Feign 的 circuit-breaker 配置提供，
 * 本装饰器仅负责重试 + 退避。
 *
 * <p>注意：仅对幂等 sink 调用重试；非幂等调用不得使用本装饰器。</p>
 */
public class ResilientFulfillmentGateway implements FulfillmentGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientFulfillmentGateway.class);

    private final FulfillmentGateway delegate;
    private final int maxAttempts;
    private final long backoffMillis;

    public ResilientFulfillmentGateway(FulfillmentGateway delegate, int maxAttempts, long backoffMillis) {
        this.delegate = delegate;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    @Override
    public FulfillmentAcceptedResponse notifyPaymentSucceeded(PaymentSucceededRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.notifyPaymentSucceeded(request);
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < maxAttempts) {
                    long wait = backoffMillis * (1L << (attempt - 1)); // 指数退避：200ms, 400ms, 800ms...
                    log.warn("履约 RPC 失败，第 {} 次重试（退避 {}ms）: {}", attempt, wait, ex.getMessage());
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }
}
