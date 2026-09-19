package com.payment.fulfillment.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.mq.EventEnvelope;
import com.payment.fulfillment.application.FulfillmentApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * fulfillment 消费侧路由单测（spec 029 / 批次 D / T43-T45 / SC-9）。
 *
 * <p>重点钉死两类翻译错误：① {@code order.paid} 的明细字段（orderItemNo / skuCode / name /
 * quantity / priceMinor / currencyCode）必须逐字段搬到 {@code ItemLine}——漏一个字段会让履约单
 * 缺内容却「创建成功」；② {@code order.cancelled} 必须构造合法 refundNo（{@code CANCEL:{orderNo}}）
 * ——空 refundNo 会让下游权益回收按空键查不到，静默不撤销。</p>
 */
class FulfillmentMqHandlersTest {

    private final FulfillmentApplicationService service = mock(FulfillmentApplicationService.class);
    private final FulfillmentMqHandlers handlers = new FulfillmentMqHandlers(service);

    private static EventEnvelope envelope(String topic, String bizNo, Map<String, Object> payload) {
        return new EventEnvelope("msg-" + bizNo, topic, topic.toUpperCase().replace('.', '_'),
                bizNo, "trace-" + bizNo, "order-service", Instant.now(), payload);
    }

    @Test
    @DisplayName("T43 order.paid → 按明细建履约单，ItemLine 字段逐一搬运不丢")
    void onOrderPaidBuildsItemLines() {
        handlers.onOrderPaid(envelope("order.paid", "ORD-1", Map.of(
                "orderNo", "ORD-1", "paymentNo", "PAY-1", "userId", "u-1", "currencyCode", "CNY",
                "items", List.of(Map.of(
                        "orderItemNo", "OI-1", "skuCode", "DEMO-SKU-101", "name", "示例商品",
                        "quantity", 2, "priceMinor", 9900L, "currencyCode", "CNY")))));

        ArgumentCaptor<PaymentSucceededRequest> captor =
                ArgumentCaptor.forClass(PaymentSucceededRequest.class);
        verify(service).acceptPaymentSucceeded(captor.capture());
        PaymentSucceededRequest req = captor.getValue();

        assertThat(req.orderNo()).isEqualTo("ORD-1");
        assertThat(req.paymentNo()).isEqualTo("PAY-1");
        assertThat(req.userId()).isEqualTo("u-1");
        assertThat(req.items()).hasSize(1);
        PaymentSucceededRequest.ItemLine line = req.items().get(0);
        assertThat(line.orderItemNo()).as("明细行号（幂等粒度的一半）").isEqualTo("OI-1");
        assertThat(line.skuCode()).isEqualTo("DEMO-SKU-101");
        assertThat(line.name()).isEqualTo("示例商品");
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.priceMinor()).isEqualTo(9900L);
        assertThat(line.currencyCode()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("T44 refund.succeeded → 终止履约，refundNo/paymentNo/orderNo 原样传递")
    void onRefundSucceededTerminates() {
        handlers.onRefundSucceeded(envelope("refund.succeeded", "TXRF-1", Map.of(
                "refundNo", "TXRF-1", "paymentNo", "PAY-1", "orderNo", "ORD-1", "userId", "u-1")));

        ArgumentCaptor<RefundFulfillmentRequest> captor =
                ArgumentCaptor.forClass(RefundFulfillmentRequest.class);
        verify(service).onRefund(captor.capture());
        RefundFulfillmentRequest req = captor.getValue();
        assertThat(req.refundNo()).isEqualTo("TXRF-1");
        assertThat(req.paymentNo()).isEqualTo("PAY-1");
        assertThat(req.orderNo()).isEqualTo("ORD-1");
        assertThat(req.userId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("T45 order.cancelled → 撤单，refundNo 合成 CANCEL:{orderNo} 且非空")
    void onOrderCancelledUsesSyntheticRefundNo() {
        handlers.onOrderCancelled(envelope("order.cancelled", "ORD-9", Map.of(
                "orderNo", "ORD-9", "userId", "u-9")));

        ArgumentCaptor<RefundFulfillmentRequest> captor =
                ArgumentCaptor.forClass(RefundFulfillmentRequest.class);
        verify(service).onRefund(captor.capture());
        RefundFulfillmentRequest req = captor.getValue();
        assertThat(req.refundNo())
                .as("撤单没有真实退款单号，必须合成且非空——空值会让下游权益回收静默失效")
                .isEqualTo("CANCEL:ORD-9");
        assertThat(req.orderNo()).isEqualTo("ORD-9");
        assertThat(req.reason()).contains("cancel");
    }

    @Test
    @DisplayName("重复投递 → 调用签名一致，由应用服务幂等吸收（INV-2）")
    void duplicateDeliveryIsIdempotentAtService() {
        EventEnvelope e = envelope("order.paid", "ORD-2", Map.of(
                "orderNo", "ORD-2", "paymentNo", "PAY-2", "userId", "u-2",
                "items", List.of(Map.of("orderItemNo", "OI-2", "skuCode", "S", "name", "n",
                        "quantity", 1, "priceMinor", 100L, "currencyCode", "CNY"))));
        handlers.onOrderPaid(e);
        handlers.onOrderPaid(e);

        verify(service, times(2)).acceptPaymentSucceeded(any(PaymentSucceededRequest.class));
    }
}
