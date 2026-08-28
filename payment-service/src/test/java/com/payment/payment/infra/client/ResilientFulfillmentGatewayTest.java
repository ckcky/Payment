package com.payment.payment.infra.client;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.FulfillmentGateway;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 弹性履约网关测试：验证幂等调用的指数退避重试与上限。
 */
class ResilientFulfillmentGatewayTest {

    private final PaymentSucceededRequest req =
            new PaymentSucceededRequest(1L, "order-1", "txn-1", "user-1", 100, "CNY");

    @Test
    void retriesThenSucceedsOnTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        FulfillmentGateway delegate = r -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new IllegalStateException("boom-" + n);
            }
            return new FulfillmentAcceptedResponse(9L, "PROCESSING");
        };
        ResilientFulfillmentGateway gateway = new ResilientFulfillmentGateway(delegate, 3, 1);

        FulfillmentAcceptedResponse res = gateway.notifyPaymentSucceeded(req);

        assertThat(res.fulfillmentId()).isEqualTo(9L);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void rethrowsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        FulfillmentGateway delegate = r -> {
            calls.incrementAndGet();
            throw new IllegalStateException("persistent");
        };
        ResilientFulfillmentGateway gateway = new ResilientFulfillmentGateway(delegate, 3, 1);

        assertThatThrownBy(() -> gateway.notifyPaymentSucceeded(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("persistent");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void succeedsWithoutRetryWhenDelegateOk() {
        AtomicInteger calls = new AtomicInteger();
        FulfillmentGateway delegate = r -> {
            calls.incrementAndGet();
            return new FulfillmentAcceptedResponse(1L, "PROCESSING");
        };
        ResilientFulfillmentGateway gateway = new ResilientFulfillmentGateway(delegate, 3, 1);

        gateway.notifyPaymentSucceeded(req);

        assertThat(calls.get()).isEqualTo(1);
    }
}
