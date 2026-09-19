package com.payment.entitlement.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.mq.EventEnvelope;
import com.payment.entitlement.application.EntitlementApplicationService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * entitlement 消费侧路由单测（spec 029 / 批次 D / T47-T48 / SC-9）。
 *
 * <p>权益是链路的**终点**：fulfillment → entitlement 的两条事件是权益唯一来源。
 * 这里断言字段搬运与「缺失必填字段时安全跳过」（跳过 = 消息 XACK，不重试、不进 DLQ，
 * 因为重试也不会让缺字段变出来；真正的修复靠上游修正后重发）。</p>
 */
class EntitlementMqHandlersTest {

    private final EntitlementApplicationService service = mock(EntitlementApplicationService.class);
    private final EntitlementMqHandlers handlers = new EntitlementMqHandlers(service);

    private static EventEnvelope envelope(String topic, String bizNo, Map<String, Object> payload) {
        return new EventEnvelope("msg-" + bizNo, topic, topic.toUpperCase().replace('.', '_'),
                bizNo, "trace-" + bizNo, "fulfillment-service", Instant.now(), payload);
    }

    @Test
    @DisplayName("T47 fulfillment.completed → 授予权益，fulfillmentId 数值化正确")
    void onFulfillmentCompletedGrants() {
        handlers.onFulfillmentCompleted(envelope("fulfillment.completed", "ORD-1",
                Map.of("fulfillmentId", 42L, "orderNo", "ORD-1", "userId", "u-1")));

        ArgumentCaptor<FulfillmentCompletedRequest> captor =
                ArgumentCaptor.forClass(FulfillmentCompletedRequest.class);
        verify(service).grantOnFulfillmentCompleted(captor.capture());
        FulfillmentCompletedRequest req = captor.getValue();
        assertThat(req.fulfillmentId()).as("授予幂等键的唯一来源").isEqualTo(42L);
        assertThat(req.orderNo()).isEqualTo("ORD-1");
        assertThat(req.userId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("T47 fulfillmentId 以字符串形态到达（Redis field 均为字符串）也能解析")
    void onFulfillmentCompletedParsesStringId() {
        handlers.onFulfillmentCompleted(envelope("fulfillment.completed", "ORD-2",
                Map.of("fulfillmentId", "77", "orderNo", "ORD-2", "userId", "u-2")));

        ArgumentCaptor<FulfillmentCompletedRequest> captor =
                ArgumentCaptor.forClass(FulfillmentCompletedRequest.class);
        verify(service).grantOnFulfillmentCompleted(captor.capture());
        assertThat(captor.getValue().fulfillmentId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("T47 缺 fulfillmentId → 安全跳过（不调用服务、不抛异常，消息 XACK）")
    void onFulfillmentCompletedSkipsWhenIdMissing() {
        handlers.onFulfillmentCompleted(envelope("fulfillment.completed", "ORD-3",
                Map.of("orderNo", "ORD-3", "userId", "u-3")));

        verify(service, never()).grantOnFulfillmentCompleted(
                org.mockito.ArgumentMatchers.any(FulfillmentCompletedRequest.class));
    }

    @Test
    @DisplayName("T48 fulfillment.revoked → 回收权益，refundNo/orderNo/reason 原样传递")
    void onFulfillmentRevokedRevokes() {
        handlers.onFulfillmentRevoked(envelope("fulfillment.revoked", "TXRF-1", Map.of(
                "refundNo", "TXRF-1", "paymentNo", "PAY-1", "orderNo", "ORD-1",
                "userId", "u-1", "reason", "refund succeeded")));

        ArgumentCaptor<RefundPostProcessRequest> captor =
                ArgumentCaptor.forClass(RefundPostProcessRequest.class);
        verify(service).revokeOnRefund(captor.capture());
        RefundPostProcessRequest req = captor.getValue();
        assertThat(req.refundNo()).isEqualTo("TXRF-1");
        assertThat(req.paymentNo()).isEqualTo("PAY-1");
        assertThat(req.orderNo()).isEqualTo("ORD-1");
        assertThat(req.reason()).isEqualTo("refund succeeded");
    }

    @Test
    @DisplayName("撤单路径：reason 为 order cancelled 时同样走回收（终态由状态机吸收）")
    void cancelledOrderReasonStillRevokes() {
        handlers.onFulfillmentRevoked(envelope("fulfillment.revoked", "CANCEL:ORD-4", Map.of(
                "refundNo", "CANCEL:ORD-4", "orderNo", "ORD-4", "userId", "u-4",
                "reason", "order cancelled")));

        ArgumentCaptor<RefundPostProcessRequest> captor =
                ArgumentCaptor.forClass(RefundPostProcessRequest.class);
        verify(service).revokeOnRefund(captor.capture());
        assertThat(captor.getValue().refundNo()).isEqualTo("CANCEL:ORD-4");
        assertThat(captor.getValue().reason()).isEqualTo("order cancelled");
    }
}
