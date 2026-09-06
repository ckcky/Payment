package com.payment.fulfillment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约应用服务（同步 RPC，spec 018 / ADR-0066）：PaymentSucceededRequest 按 order_item 粒度
 * 逐明细创建 DELIVERED 履约并逐条触发权益授予；幂等粒度 = (sourcePaymentNo, orderItemId)——
 * 重复通知不重复创建、不重复触发权益；onRefund 取消全部 PENDING。
 */
class FulfillmentApplicationServiceTest {

    private FulfillmentRepository repository;
    private RecordingEntitlementGateway gateway;
    private FulfillmentApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFulfillmentRepository();
        gateway = new RecordingEntitlementGateway(new ArrayList<>());
        service = new FulfillmentApplicationService(repository, gateway, new NoopBusinessMetrics());
    }

    private static PaymentSucceededRequest.ItemLine itemLine(String orderItemNo) {
        return new PaymentSucceededRequest.ItemLine(orderItemNo, "SKU-" + orderItemNo,
                "商品" + orderItemNo, 1, 625L, "USD");
    }

    private static PaymentSucceededRequest paymentSucceededRequest(String... orderItemNos) {
        List<PaymentSucceededRequest.ItemLine> items = java.util.Arrays.stream(orderItemNos)
                .map(FulfillmentApplicationServiceTest::itemLine)
                .toList();
        return new PaymentSucceededRequest("pay-1", "order_1", "txn_1", "user_1", 1250L, "USD", items);
    }

    @Test
    void firstPaymentSuccessCreatesPerItemFulfillmentsAndNotifiesEntitlementPerItem() {
        List<Fulfillment> fulfillments = service.acceptPaymentSucceeded(
                paymentSucceededRequest("OI-1", "OI-2"));

        assertThat(fulfillments).hasSize(2);
        assertThat(fulfillments).allSatisfy(f -> {
            assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
            assertThat(f.getOrderNo()).isEqualTo("order_1");
            assertThat(f.getSourcePaymentNo()).isEqualTo("pay-1");
        });
        assertThat(fulfillments).extracting(Fulfillment::getOrderItemId)
                .containsExactly("OI-1", "OI-2");

        // 每条履约各自通知权益（授予链零改动，幂等键=履约行主键）
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests).extracting(FulfillmentCompletedRequest::fulfillmentId)
                .containsExactly(fulfillments.get(0).getId(), fulfillments.get(1).getId());
    }

    @Test
    void repeatedNotificationIsIdempotentPerItem() {
        PaymentSucceededRequest request = paymentSucceededRequest("OI-1", "OI-2");

        List<Fulfillment> first = service.acceptPaymentSucceeded(request);
        List<Fulfillment> second = service.acceptPaymentSucceeded(request);

        assertThat(second).extracting(Fulfillment::getId)
                .containsExactly(first.get(0).getId(), first.get(1).getId());
        assertThat(gateway.requests).hasSize(2); // 不重复触发权益
    }

    @Test
    void partiallyRepeatedNotificationOnlyCreatesMissingItems() {
        service.acceptPaymentSucceeded(paymentSucceededRequest("OI-1"));
        List<Fulfillment> both = service.acceptPaymentSucceeded(
                paymentSucceededRequest("OI-1", "OI-2"));

        assertThat(both).hasSize(2);
        assertThat(repository.findByOrderNo("order_1")).hasSize(2); // OI-1 未重复建
        assertThat(gateway.requests).hasSize(2); // 仅 OI-2 触发过授予
    }

    @Test
    void missingItemsIsContractViolation() {
        PaymentSucceededRequest noItems = PaymentSucceededRequest.withoutItems(
                "pay-1", "order_1", "txn_1", "user_1", 1250L, "USD");

        assertThatThrownBy(() -> service.acceptPaymentSucceeded(noItems))
                .isInstanceOf(BizException.class);
    }

    @Test
    void onRefundCancelsAllPendingFulfillments() {
        service.acceptPaymentSucceeded(paymentSucceededRequest("OI-1", "OI-2"));

        // 再造一条 PENDING 履约（未 start，等同「通知已受理但尚未处理」的悬挂态）
        Fulfillment pending = service.newFulfillment("order_1", "OI-3", "pay-1");
        repository.save(pending);
        assertThat(pending.getStatus()).isEqualTo(FulfillmentStatus.PENDING);

        com.payment.common.dto.rpc.RefundFulfillmentResponse resp = service.onRefund(
                new com.payment.common.dto.rpc.RefundFulfillmentRequest(
                        "RF-1", "pay-1", "order_1", "user_1", "demo refund"));

        assertThat(resp.status()).isEqualTo("CANCELLED"); // PENDING 那条被取消
        assertThat(repository.findBySourcePaymentNoAndOrderItemId("pay-1", "OI-3"))
                .hasValueSatisfying(f -> assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED));
        // 已交付的两条不受影响
        assertThat(repository.findBySourcePaymentNoAndOrderItemId("pay-1", "OI-1"))
                .hasValueSatisfying(f -> assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED));
        assertThat(repository.findByOrderNo("order_1")).hasSize(3);
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
