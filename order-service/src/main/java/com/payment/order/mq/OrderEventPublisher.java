package com.payment.order.mq;

import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqProperties;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.TransactionalProducer;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.RefundOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * order 侧事件发布门面（spec 029 / 批次 C / FR-202、204、205）。
 *
 * <p>负载一律由 order 从**自己的库**富化（INV-4 / FR-210）：{@code order.paid} 携带明细行
 * （orderItemNo / skuId / quantity），使 catalog 与 fulfillment 无需反查 order。</p>
 *
 * <p>{@code payment.mq.enabled=false} 时本 Bean 不注册，调用方按
 * {@code MqProperties.isEnabled()} 回落同步 Feign（FR-306）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final TransactionalProducer producer;
    private final MqProperties props;

    public OrderEventPublisher(TransactionalProducer producer, MqProperties props) {
        this.producer = producer;
        this.props = props;
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    /**
     * 发布 {@code order.paid}（广播，FR-202）。
     *
     * <p><b>调用时机</b>：订单落 PAID 的**本地事务提交之后**（INV-3）。此方法内部走
     * {@code sendInTransaction} 会把 prepare 包在传入的事务动作之前，故此处传入的是
     * 「已提交的既有事实」——订单已 PAID，回查真相表必然判定 COMMIT。</p>
     */
    public void publishOrderPaid(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("paymentNo", order.getPaymentNo());
        payload.put("userId", order.getUserId());
        payload.put("merchantId", order.getMerchantId());
        payload.put("currencyCode", order.getCurrencyCode());
        payload.put("items", itemLines(order));
        publish(MqTopics.ORDER_PAID, order.getOrderNo(), payload);
        log.info("MQ 发布 order.paid [orderNo={}, paymentNo={}]", order.getOrderNo(), order.getPaymentNo());
    }

    /**
     * 发布 {@code refund.succeeded}（广播，FR-204）：携带订单明细供 catalog 回补秒杀、
     * fulfillment 终止履约。
     */
    public void publishRefundSucceeded(RefundOrder refund, Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundNo", refund.getRefundNo());
        payload.put("paymentNo", refund.getPaymentNo());
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("amountMinor", refund.getAmountMinor());
        payload.put("currencyCode", refund.getCurrencyCode());
        payload.put("items", itemLines(order));
        publish(MqTopics.REFUND_SUCCEEDED, refund.getRefundNo(), payload);
        log.info("MQ 发布 refund.succeeded [refundNo={}, orderNo={}]", refund.getRefundNo(), order.getOrderNo());
    }

    /**
     * 发布 {@code order.cancelled}（广播，FR-205，新增能力）：订单超时关单后通知
     * catalog（释放预占）、payment（标记不可受理）、fulfillment（撤单）。
     */
    public void publishOrderCancelled(Order order, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("reason", reason == null ? "TIMEOUT" : reason);
        payload.put("items", itemLines(order));
        publish(MqTopics.ORDER_CANCELLED, order.getOrderNo(), payload);
        log.info("MQ 发布 order.cancelled [orderNo={}, reason={}]", order.getOrderNo(), payload.get("reason"));
    }

    /**
     * 发布一条事件（事务已提交后调用）：先 prepare 半消息、随即 commit 可见。
     *
     * <p><b>为什么不是 sendInTransaction</b>：本门面在业务本地事务**提交之后**调用（INV-3 要求
     * 「先事务后可见」，此处事务已提交，事实已成）。此时不存在「事务回滚需撤销消息」的场景，
     * 故 prepare→commit 连做即可；若进程在两步之间崩溃，半消息由回查按真相表补投——订单已落库，
     * checker 判定 COMMIT 补投，语义正确。</p>
     */
    private void publish(String topic, String bizNo, Map<String, Object> payload) {
        EventEnvelope envelope = producer.envelope(topic, bizNo, payload);
        producer.prepare(envelope);
        producer.commit(envelope);
    }

    /** 明细行富化（INV-4：以本库 order_items 为唯一事实源）。 */
    private static List<Map<String, Object>> itemLines(Order order) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (order.getItems() == null) {
            return items;
        }
        for (OrderItem item : order.getItems()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderItemNo", item.getOrderItemNo());
            line.put("skuId", item.getSkuId());
            line.put("skuCode", item.getSkuCode());
            line.put("name", item.getName());
            line.put("quantity", item.getQuantity());
            line.put("priceMinor", item.getPriceMinor());
            line.put("currencyCode", item.getCurrencyCode());
            items.add(line);
        }
        return items;
    }

    /** 供调用方判断是否走通道（避免在 false 时注入本 Bean 失败）。 */
    public static boolean isEnabled(MqProperties props) {
        return props != null && props.isEnabled();
    }

    /** 预构建信封（供需要显式 prepare/commit 的高级场景）。 */
    public EventEnvelope envelope(String topic, String bizNo, Map<String, Object> payload) {
        return producer.envelope(topic, bizNo, payload);
    }
}
