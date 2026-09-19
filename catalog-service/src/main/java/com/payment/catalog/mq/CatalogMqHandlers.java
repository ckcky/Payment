package com.payment.catalog.mq;

import com.payment.catalog.application.StockApplicationService;
import com.payment.catalog.application.seckill.SeckillStockService;
import com.payment.common.mq.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * catalog 侧消费路由（spec 029 / 批次 D / T40-T42、FR-303）。
 *
 * <ul>
 *   <li>{@code order.paid} → {@code confirm} 库存（幂等键 reservationId，既有 {@code confirm}
 *       内部按 CONFIRMED 状态吸收重复）</li>
 *   <li>{@code refund.succeeded} → {@code rollbackSeckill} 秒杀回补（幂等键
 *       {@code refund:{TXRF}:sku:{skuId}}，由 {@code SeckillStockService.rollback} 的
 *       {@code exists} 守卫天然幂等——普通商品无配额键自动跳过）</li>
 *   <li>{@code order.cancelled} → {@code release} 释放预占 + 秒杀回补</li>
 * </ul>
 *
 * <p>负载明细由 order 从本库富化（INV-4），catalog 不反查 order。</p>
 */
@Component
public class CatalogMqHandlers {

    private static final Logger log = LoggerFactory.getLogger(CatalogMqHandlers.class);

    /** 与 order 侧 reservationId 公式保持一致（ADR-0063）。 */
    private static final String RESERVATION_PREFIX = "order:";

    private final StockApplicationService stockService;
    private final SeckillStockService seckillService;

    public CatalogMqHandlers(StockApplicationService stockService, SeckillStockService seckillService) {
        this.stockService = stockService;
        this.seckillService = seckillService;
    }

    /** 消费 {@code order.paid}：确认扣减本订单各明细预占库存（幂等：reservationId）。 */
    void onOrderPaid(EventEnvelope envelope) {
        String orderNo = envelope.str("orderNo");
        for (Map<String, Object> item : items(envelope)) {
            String skuId = str(item, "skuId");
            long quantity = num(item, "quantity");
            stockService.confirm(reservationId(orderNo, skuId), Long.valueOf(skuId), quantity,
                    "PAY:" + envelope.str("paymentNo"));
            log.info("MQ 消费 order.paid → catalog confirm [orderNo={}, skuId={}, qty={}, traceId={}]",
                    orderNo, skuId, quantity, envelope.traceId());
        }
    }

    /** 消费 {@code refund.succeeded}：秒杀配额回补（幂等：SeckillStockService exists 守卫）。 */
    void onRefundSucceeded(EventEnvelope envelope) {
        String refundNo = envelope.str("refundNo");
        for (Map<String, Object> item : items(envelope)) {
            String skuId = str(item, "skuId");
            long quantity = num(item, "quantity");
            log.debug("MQ 消费 refund.succeeded → catalog 秒杀回补 [refundNo={}, skuId={}, qty={}]",
                    refundNo, skuId, quantity);
            seckillService.rollback(Long.valueOf(skuId), quantity);
        }
        log.info("MQ 消费 refund.succeeded → catalog 回补完成 [refundNo={}, lines={}, traceId={}]",
                refundNo, items(envelope).size(), envelope.traceId());
    }

    /** 消费 {@code order.cancelled}：释放预占库存 + 回补秒杀配额（幂等：release 状态机吸收）。 */
    void onOrderCancelled(EventEnvelope envelope) {
        String orderNo = envelope.str("orderNo");
        for (Map<String, Object> item : items(envelope)) {
            String skuId = str(item, "skuId");
            long quantity = num(item, "quantity");
            stockService.release(reservationId(orderNo, skuId), Long.valueOf(skuId), quantity);
            seckillService.rollback(Long.valueOf(skuId), quantity);
            log.info("MQ 消费 order.cancelled → catalog release [orderNo={}, skuId={}, qty={}, traceId={}]",
                    orderNo, skuId, quantity, envelope.traceId());
        }
    }

    /** 明细行（order 富化，INV-4）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(EventEnvelope envelope) {
        Object raw = envelope.get("items");
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static String str(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static String reservationId(String orderNo, String skuId) {
        return RESERVATION_PREFIX + orderNo + ":sku:" + skuId;
    }
}
