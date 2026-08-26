package com.payment.fulfillment.application;

import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import com.payment.fulfillment.domain.FulfillmentStatus;
import com.payment.fulfillment.infra.InMemoryFulfillmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履约应用服务（同步 RPC）：PaymentSucceededRequest → 创建 DELIVERED 履约并触发权益授予；
 * 同一 paymentId 幂等——不重复创建、不重复触发权益。
 */
class FulfillmentApplicationServiceTest {

    private FulfillmentRepository repository;
    private RecordingEntitlementGateway gateway;
    private FulfillmentApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFulfillmentRepository();
        gateway = new RecordingEntitlementGateway(new ArrayList<>());
        service = new FulfillmentApplicationService(repository, gateway);
    }

    private static PaymentSucceededRequest paymentSucceededRequest() {
        return new PaymentSucceededRequest(1L, "order_1", "txn_1", "user_1", 1250L, "USD");
    }

    @Test
    void firstPaymentSuccessCreatesDeliveredFulfillmentAndNotifiesEntitlementOnce() {
        Fulfillment fulfillment = service.acceptPaymentSucceeded(paymentSucceededRequest());

        assertThat(fulfillment.getId()).isEqualTo(1L);
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(fulfillment.getOrderId()).isEqualTo("order_1");
        assertThat(fulfillment.getSourcePaymentId()).isEqualTo("1");

        assertThat(gateway.requests).hasSize(1);
        FulfillmentCompletedRequest request = gateway.requests.get(0);
        assertThat(request.fulfillmentId()).isEqualTo(1L);
        assertThat(request.orderId()).isEqualTo("order_1");
        assertThat(request.userId()).isEqualTo("user_1");
    }

    @Test
    void repeatedPaymentIdIsIdempotent() {
        PaymentSucceededRequest request = paymentSucceededRequest();

        Fulfillment first = service.acceptPaymentSucceeded(request);
        Fulfillment second = service.acceptPaymentSucceeded(request);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(repository.findById(2L)).isEmpty();
        assertThat(gateway.requests).hasSize(1);
    }

    private record RecordingEntitlementGateway(List<FulfillmentCompletedRequest> requests)
            implements EntitlementGateway {

        @Override
        public EntitlementGrantedResponse notifyFulfillmentCompleted(FulfillmentCompletedRequest request) {
            requests.add(request);
            return new EntitlementGrantedResponse(1L, "GRANTED");
        }
    }
}
