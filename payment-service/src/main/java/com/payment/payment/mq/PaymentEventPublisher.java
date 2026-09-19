package com.payment.payment.mq;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.TransactionalProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * payment 侧事件发布门面（spec 029 / 批次 C / FR-201、203，T28-T30）。
 *
 * <ul>
 *   <li>{@code payment.succeeded}（点对点 → order）：替代 {@code OrderGateway.notifyPaymentSucceeded}
 *       （FR-201）。</li>
 *   <li>{@code refund.result}（点对点 → order）：替代 {@code OrderGateway.notifyRefundResult}（FR-203）。</li>
 * </ul>
 *
 * <p><b>调用纪律（FR-306 / T28-T30）</b>：调用点把「本地落库」作为 {@code txAction} 传进来，
 * 由 {@link TransactionalProducer#sendInTransaction} 收敛 prepare → tx → commit/rollback：
 * 事务抛异常则半消息 rollback（零投递，INV-3 / SC-2）。</p>
 *
 * <p>{@code payment.mq.enabled=false} 时本 Bean 不注册，调用方回落既有同步 Feign（FR-306）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final TransactionalProducer producer;

    public PaymentEventPublisher(TransactionalProducer producer) {
        this.producer = producer;
    }

    /**
     * 事务消息发送：{@code payment.succeeded}（FR-201）。
     *
     * @param request  成功通知负载
     * @param txAction 本地事务（payment 落库），抛异常则不投递
     */
    public <T> T inTransactionPaymentSucceeded(PaymentSucceededRequest request, Supplier<T> txAction) {
        return producer.sendInTransaction(MqTopics.PAYMENT_SUCCEEDED, request.paymentNo(),
                payload(request), txAction);
    }

    /**
     * 事务消息发送：{@code refund.result}（FR-203）。
     *
     * @param notification 退款终态负载（TXRF+PMRF 双号，ADR-0067）
     * @param txAction     本地事务（refunds 落库），抛异常则不投递
     */
    public <T> T inTransactionRefundResult(RefundResultNotification notification, Supplier<T> txAction) {
        return producer.sendInTransaction(MqTopics.REFUND_RESULT, notification.transactionRefundNo(),
                payload(notification), txAction);
    }

    /** 本地事实已提交后直接发布（用于既有代码结构不适合传 txAction 的场景，如事件转发）。 */
    public void publishPaymentSucceeded(PaymentSucceededRequest request) {
        publish(MqTopics.PAYMENT_SUCCEEDED, request.paymentNo(), payload(request));
        log.info("MQ 发布 payment.succeeded [paymentNo={}, orderNo={}]",
                request.paymentNo(), request.orderNo());
    }

    /** 本地事实已提交后直接发布 {@code refund.result}。 */
    public void publishRefundResult(RefundResultNotification notification) {
        publish(MqTopics.REFUND_RESULT, notification.transactionRefundNo(), payload(notification));
        log.info("MQ 发布 refund.result [txrf={}, status={}]",
                notification.transactionRefundNo(), notification.status());
    }

    private void publish(String topic, String bizNo, Map<String, Object> payload) {
        EventEnvelope envelope = producer.envelope(topic, bizNo, payload);
        producer.prepare(envelope);
        producer.commit(envelope);
    }

    private static Map<String, Object> payload(PaymentSucceededRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentNo", request.paymentNo());
        payload.put("orderNo", request.orderNo());
        payload.put("transactionNo", request.transactionNo());
        payload.put("userId", request.userId());
        payload.put("amountMinor", request.amountMinor());
        payload.put("currencyCode", request.currencyCode());
        return payload;
    }

    private static Map<String, Object> payload(RefundResultNotification n) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionRefundNo", n.transactionRefundNo());
        payload.put("paymentRefundNo", n.paymentRefundNo());
        payload.put("transactionNo", n.transactionNo());
        payload.put("orderNo", n.orderNo());
        payload.put("paymentNo", n.paymentNo());
        payload.put("amountMinor", n.amountMinor());
        payload.put("currencyCode", n.currencyCode());
        payload.put("status", n.status());
        payload.put("failureReason", n.failureReason());
        return payload;
    }
}
