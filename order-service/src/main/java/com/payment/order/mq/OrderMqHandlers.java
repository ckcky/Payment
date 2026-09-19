package com.payment.order.mq;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqException;
import com.payment.common.mq.TransactionChecker;
import com.payment.order.application.TransactionApplicationService;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.RefundOrderStatus;
import com.payment.order.domain.TransactionRefundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * order 侧消费路由 + 回查 checker（spec 029 / 批次 C / FR-302）。
 *
 * <p><b>消费</b>：把 {@code payment.succeeded} / {@code refund.result} 的信封 payload 还原为
 * 既有 DTO，委派 {@link TransactionApplicationService} 既有的幂等入口——通道只替换「送达方式」，
 * 业务语义与同步 Feign 完全一致（INV-2：下游已幂等）。</p>
 *
 * <p><b>回查</b>：三个 checker 对应 order 生产的三类事件，去自己的库判事实是否落定（INV-4）。</p>
 */
@Component
public class OrderMqHandlers {

    private static final Logger log = LoggerFactory.getLogger(OrderMqHandlers.class);

    private final TransactionApplicationService transactionLayer;
    private final OrderRepository orderRepository;
    private final TransactionRefundRepository transactionRefundRepository;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public OrderMqHandlers(TransactionApplicationService transactionLayer,
                           OrderRepository orderRepository,
                           TransactionRefundRepository transactionRefundRepository) {
        this.transactionLayer = transactionLayer;
        this.orderRepository = orderRepository;
        this.transactionRefundRepository = transactionRefundRepository;
    }

    // ---- 消费路由 ----

    /** 消费 payment.succeeded → 委派既有交易层入口（内含 surplus 判定与 order 层编排）。 */
    void onPaymentSucceeded(EventEnvelope envelope) {
        PaymentSucceededRequest request = new PaymentSucceededRequest(
                envelope.str("paymentNo"),
                envelope.str("orderNo"),
                envelope.str("transactionNo"),
                envelope.str("userId"),
                envelope.num("amountMinor") == null ? 0L : envelope.num("amountMinor"),
                envelope.str("currencyCode"),
                // FR-202/INV-4：明细由 order 层从本库 order_items 富化，不信任上游快照
                null);
        log.info("MQ 消费 payment.succeeded → order 交易层 [paymentNo={}, orderNo={}, traceId={}]",
                request.paymentNo(), request.orderNo(), envelope.traceId());
        transactionLayer.onPaymentSucceeded(request);
    }

    /** 消费 refund.result → 委派既有退款收口入口（含秒杀回补 / 履约终止）。 */
    void onRefundResult(EventEnvelope envelope) {
        RefundResultNotification notification = new RefundResultNotification(
                envelope.str("transactionRefundNo"),
                envelope.str("paymentRefundNo"),
                envelope.str("transactionNo"),
                envelope.str("orderNo"),
                envelope.str("paymentNo"),
                envelope.num("amountMinor") == null ? 0L : envelope.num("amountMinor"),
                envelope.str("currencyCode"),
                envelope.str("status"),
                envelope.str("failureReason"));
        log.info("MQ 消费 refund.result → order 退款收口 [txrf={}, status={}, traceId={}]",
                notification.transactionRefundNo(), notification.status(), envelope.traceId());
        transactionLayer.onRefundResult(notification);
    }

    // ---- 回查 checker（FR-302）----

    /** {@code order.paid} 回查：orders 是否已 PAID（且支付单匹配）。 */
    TransactionChecker orderPaidChecker() {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return com.payment.common.mq.MqTopics.ORDER_PAID.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                String orderNo = e.str("orderNo");
                if (orderNo == null) {
                    return LocalTxState.ROLLBACK;
                }
                return orderRepository.findByOrderNo(orderNo)
                        .map(o -> o.getStatus() == OrderStatus.PAID
                                || o.getStatus() == OrderStatus.FULFILLING
                                || o.getStatus() == OrderStatus.COMPLETED
                                ? LocalTxState.COMMIT : LocalTxState.ROLLBACK)
                        .orElse(LocalTxState.ROLLBACK);
            }
        };
    }

    /** {@code refund.succeeded} 回查：transaction_refunds 是否已 SUCCEEDED。 */
    TransactionChecker refundSucceededChecker() {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return com.payment.common.mq.MqTopics.REFUND_SUCCEEDED.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                String refundNo = e.str("refundNo");
                if (refundNo == null) {
                    return LocalTxState.ROLLBACK;
                }
                return transactionRefundRepository.findByRefundNo(refundNo)
                        .map(r -> r.getStatus() == RefundOrderStatus.SUCCEEDED
                                ? LocalTxState.COMMIT : LocalTxState.ROLLBACK)
                        .orElse(LocalTxState.ROLLBACK);
            }
        };
    }

    /** {@code order.cancelled} 回查：orders 是否已 CANCELLED / CLOSED。 */
    TransactionChecker orderCancelledChecker() {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return com.payment.common.mq.MqTopics.ORDER_CANCELLED.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                String orderNo = e.str("orderNo");
                if (orderNo == null) {
                    return LocalTxState.ROLLBACK;
                }
                return orderRepository.findByOrderNo(orderNo)
                        .map(o -> o.getStatus() == OrderStatus.CANCELLED
                                || o.getStatus() == OrderStatus.CLOSED
                                ? LocalTxState.COMMIT : LocalTxState.ROLLBACK)
                        .orElse(LocalTxState.ROLLBACK);
            }
        };
    }

    /** 反序列化 payload 中嵌套对象列表（如 order.paid 的 items）为 DTO。 */
    <T> List<T> list(EventEnvelope e, String key, Class<T> type) {
        Object raw = e.get(key);
        if (raw == null) {
            return List.of();
        }
        try {
            return mapper.convertValue(raw,
                    mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IllegalArgumentException ex) {
            throw new MqException("payload 反序列化失败 [key=" + key + "]", ex);
        }
    }

    /** 供生产侧读取订单（富化 order.paid 负载用）。 */
    public Order requireOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new MqException("order not found: " + orderNo));
    }

    /** 供生产侧读取退款单（富化 refund.succeeded 负载用）。 */
    public RefundOrder requireRefund(String refundNo) {
        return transactionRefundRepository.findByRefundNo(refundNo)
                .orElseThrow(() -> new MqException("refund not found: " + refundNo));
    }
}
