package com.payment.fulfillment.mq;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.mq.EventEnvelope;
import com.payment.fulfillment.application.FulfillmentApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * fulfillment 侧消费路由（spec 029 / 批次 D / T43-T45、FR-304）。
 *
 * <ul>
 *   <li>{@code order.paid} → 按明细建履约单（{@code acceptPaymentSucceeded}，逐明细幂等）；</li>
 *   <li>{@code refund.succeeded} → 终止履约（{@code onRefund}，并触发权益撤销）；</li>
 *   <li>{@code order.cancelled} → 撤单（仅 PENDING 可撤，其余状态机吸收）。</li>
 * </ul>
 *
 * <p>负载明细由 order 从本库富化（INV-4），fulfillment 不反查 order。</p>
 */
@Component
public class FulfillmentMqHandlers {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentMqHandlers.class);

    private final FulfillmentApplicationService fulfillmentService;

    public FulfillmentMqHandlers(FulfillmentApplicationService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    /** 消费 {@code order.paid}：按明细建履约单（幂等粒度 (sourcePaymentNo, orderItemNo)）。 */
    void onOrderPaid(EventEnvelope envelope) {
        PaymentSucceededRequest request = new PaymentSucceededRequest(
                envelope.str("paymentNo"), envelope.str("orderNo"), null,
                envelope.str("userId"), 0L, envelope.str("currencyCode"), itemLines(envelope));
        fulfillmentService.acceptPaymentSucceeded(request);
        log.info("MQ 消费 order.paid → fulfillment 建履约 [orderNo={}, paymentNo={}, lines={}, traceId={}]",
                request.orderNo(), request.paymentNo(), request.items().size(), envelope.traceId());
    }

    /** 消费 {@code refund.succeeded}：终止履约（撤 PENDING + 触发权益撤销）。 */
    void onRefundSucceeded(EventEnvelope envelope) {
        fulfillmentService.onRefund(new RefundFulfillmentRequest(
                envelope.str("refundNo"), envelope.str("paymentNo"), envelope.str("orderNo"),
                envelope.str("userId"), "refund succeeded"));
        log.info("MQ 消费 refund.succeeded → fulfillment 终止 [refundNo={}, orderNo={}, traceId={}]",
                envelope.str("refundNo"), envelope.str("orderNo"), envelope.traceId());
    }

    /** 消费 {@code order.cancelled}：撤单（同 onRefund 语义，原因标 order cancelled）。 */
    void onOrderCancelled(EventEnvelope envelope) {
        fulfillmentService.onRefund(new RefundFulfillmentRequest(
                "CANCEL:" + envelope.str("orderNo"), null, envelope.str("orderNo"),
                envelope.str("userId"), "order cancelled"));
        log.info("MQ 消费 order.cancelled → fulfillment 撤单 [orderNo={}, traceId={}]",
                envelope.str("orderNo"), envelope.traceId());
    }

    /** order.paid 的 items 明细（order 富化）→ fulfillment 所需的 ItemLine。 */
    @SuppressWarnings("unchecked")
    private static List<PaymentSucceededRequest.ItemLine> itemLines(EventEnvelope envelope) {
        Object raw = envelope.get("items");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<PaymentSucceededRequest.ItemLine> lines = new ArrayList<>(list.size());
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            lines.add(new PaymentSucceededRequest.ItemLine(
                    str(m, "orderItemNo"), str(m, "skuCode"), str(m, "name"),
                    (int) num(m, "quantity"), num(m, "priceMinor"), str(m, "currencyCode")));
        }
        return lines;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }
}
