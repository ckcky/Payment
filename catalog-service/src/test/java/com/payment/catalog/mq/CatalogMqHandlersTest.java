package com.payment.catalog.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.payment.catalog.application.StockApplicationService;
import com.payment.catalog.application.seckill.SeckillStockService;
import com.payment.common.mq.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * catalog 消费侧路由单测（spec 029 / 批次 D / T40-T42 / SC-9）。
 *
 * <p>这层测试的价值：消费路由承载「事件 → 应用服务调用」的**翻译职责**——事件字段名写错、
 * 幂等键公式（{@code order:{orderNo}:sku:{skuId}}）与 order 侧不一致、多明细只处理第一条，
 * 这些错误在集成测试里都会被「业务最终仍成功」掩盖（因为幂等键不匹配时 confirm 会失败但被吞，
 * 或恰好只有一条明细）。这里逐个断言调用签名，把翻译层钉死。</p>
 */
class CatalogMqHandlersTest {

    private final StockApplicationService stockService = mock(StockApplicationService.class);
    private final SeckillStockService seckillService = mock(SeckillStockService.class);
    private final CatalogMqHandlers handlers = new CatalogMqHandlers(stockService, seckillService);

    private static EventEnvelope envelope(String topic, String bizNo, Map<String, Object> payload) {
        return new EventEnvelope("msg-" + bizNo, topic, topic.toUpperCase().replace('.', '_'),
                bizNo, "trace-" + bizNo, "order-service", Instant.now(), payload);
    }

    private static Map<String, Object> items(long skuId, long qty) {
        return Map.of("items", List.of(Map.of("skuId", skuId, "quantity", qty)));
    }

    @Test
    @DisplayName("T40 order.paid → 按明细 confirm，幂等键 reservationId 与 order 侧公式一致")
    void onOrderPaidConfirmsWithReservationId() {
        handlers.onOrderPaid(envelope("order.paid", "ORD-1",
                Map.of("orderNo", "ORD-1", "paymentNo", "PAY-1",
                        "items", List.of(Map.of("skuId", 101L, "quantity", 2L)))));

        // 幂等键公式：order:{orderNo}:sku:{skuId}（ADR-0063，必须与 order 侧完全一致）
        verify(stockService).confirm(eq("order:ORD-1:sku:101"), eq(101L), eq(2L), eq("PAY:PAY-1"));
        // order.paid 不动秒杀配额（那是 refund / cancelled 的职责）
        verify(seckillService, never()).rollback(anyLong(), anyLong());
    }

    @Test
    @DisplayName("T40 多明细逐条处理（不得只处理第一条）")
    void onOrderPaidHandlesEveryLine() {
        handlers.onOrderPaid(envelope("order.paid", "ORD-2",
                Map.of("orderNo", "ORD-2", "paymentNo", "PAY-2",
                        "items", List.of(Map.of("skuId", 101L, "quantity", 1L),
                                Map.of("skuId", 202L, "quantity", 3L)))));

        verify(stockService).confirm(eq("order:ORD-2:sku:101"), eq(101L), eq(1L), anyString());
        verify(stockService).confirm(eq("order:ORD-2:sku:202"), eq(202L), eq(3L), anyString());
        verify(stockService, times(2)).confirm(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("T41 refund.succeeded → 秒杀回补，按明细逐条 rollback")
    void onRefundSucceededRollsBackSeckill() {
        handlers.onRefundSucceeded(envelope("refund.succeeded", "TXRF-1",
                Map.of("refundNo", "TXRF-1", "orderNo", "ORD-3",
                        "items", List.of(Map.of("skuId", 101L, "quantity", 2L)))));

        verify(seckillService).rollback(101L, 2L);
        // 退款不 confirm / release 普通库存（沿用现状：已确认预占不回滚）
        verify(stockService, never()).confirm(anyString(), anyLong(), anyLong(), anyString());
        verify(stockService, never()).release(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("T42 order.cancelled → release 预占 + 秒杀回补（两者都做）")
    void onOrderCancelledReleasesAndRollsBack() {
        handlers.onOrderCancelled(envelope("order.cancelled", "ORD-4",
                Map.of("orderNo", "ORD-4",
                        "items", List.of(Map.of("skuId", 101L, "quantity", 1L)))));

        verify(stockService).release(eq("order:ORD-4:sku:101"), eq(101L), eq(1L));
        verify(seckillService).rollback(101L, 1L);
    }

    @Test
    @DisplayName("重复投递（同负载第二次消费）→ 调用签名相同，由下游状态机幂等吸收（INV-2）")
    void duplicateDeliveryProducesSameIdempotentCalls() {
        EventEnvelope e = envelope("order.paid", "ORD-5",
                Map.of("orderNo", "ORD-5", "paymentNo", "PAY-5",
                        "items", List.of(Map.of("skuId", 101L, "quantity", 1L))));
        handlers.onOrderPaid(e);
        handlers.onOrderPaid(e);

        // 两次调用参数完全一致 → 下游按 reservationId + CONFIRMED 状态吸收重复
        ArgumentCaptor<String> reservationId = ArgumentCaptor.forClass(String.class);
        verify(stockService, times(2)).confirm(reservationId.capture(), eq(101L), eq(1L), anyString());
        assertThat(reservationId.getAllValues())
                .as("两次投递的幂等键必须相同，否则重复会变成第二笔扣减")
                .containsExactly("order:ORD-5:sku:101", "order:ORD-5:sku:101");
    }

    @Test
    @DisplayName("负载缺 items → 不抛异常（空操作），消息正常 XACK 不进 DLQ")
    void missingItemsIsNoOp() {
        handlers.onOrderPaid(envelope("order.paid", "ORD-6", Map.of("orderNo", "ORD-6")));

        verify(stockService, never()).confirm(anyString(), anyLong(), anyLong(), anyString());
    }
}
