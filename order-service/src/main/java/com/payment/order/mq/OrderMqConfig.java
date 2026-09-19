package com.payment.order.mq;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.mq.MqConsumerRuntime;
import com.payment.common.mq.MqProperties;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.StreamConsumer;
import com.payment.common.mq.TransactionChecker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * order 侧消息通道装配（spec 029 / 批次 C / FR-302）。
 *
 * <p>生产：{@code order.paid} / {@code refund.succeeded} / {@code order.cancelled}；
 * 消费：{@code payment.succeeded} / {@code refund.result}。</p>
 *
 * <p>{@code payment.mq.enabled=false} 时本配置整体不生效，starter 的事务生产者也被
 * {@code @ConditionalOnProperty} 抑制——生产方回落既有同步 Feign（FR-306）。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderMqConfig {

    /** 消费组名（order 侧订阅 payment.* 两个点对点 topic）。 */
    private static final String GROUP = "order";

    /** trace 消费组名（订阅全部 topic，落轨迹表，FR-402）。 */
    private static final String TRACE_GROUP = "trace";

    /** order 消费 {@code payment.succeeded} 与 {@code refund.result}（点对点）。 */
    @Bean
    public MqConsumerRuntime orderMqConsumerRuntime(StringRedisTemplate redis, MqProperties props,
                                                    BusinessMetrics metrics, OrderMqHandlers handlers) {
        StreamConsumer paymentSucceeded = new StreamConsumer(redis, props, metrics,
                MqTopics.PAYMENT_SUCCEEDED, GROUP, "order-ps",
                handlers::onPaymentSucceeded, null);
        StreamConsumer refundResult = new StreamConsumer(redis, props, metrics,
                MqTopics.REFUND_RESULT, GROUP, "order-rr",
                handlers::onRefundResult, null);
        return new MqConsumerRuntime().register(paymentSucceeded).register(refundResult);
    }

    /**
     * trace 消费组（spec 029 / T52、FR-402）：订阅全部 7 个 topic，各自独立位点与独立组名，
     * 落 {@code order_event_log}（只读投影，INV-5）。trace 组故障不影响业务组。
     */
    @Bean
    public MqConsumerRuntime traceMqConsumerRuntime(StringRedisTemplate redis, MqProperties props,
                                                    BusinessMetrics metrics, OrderTraceHandler traceHandler) {
        MqConsumerRuntime runtime = new MqConsumerRuntime();
        for (String topic : MqTopics.ALL) {
            runtime.register(new StreamConsumer(redis, props, metrics,
                    topic, TRACE_GROUP, "trace-" + topic, traceHandler::onEvent, null));
        }
        return runtime;
    }

    /**
     * 三个回查 checker（FR-302）：orders 是否 PAID / transaction_refunds 是否 SUCCEEDED /
     * orders 是否 CANCELLED·CLOSED。以独立 Bean 注册，由 starter 的
     * {@code HalfMessageScanner} 通过 {@code ObjectProvider<TransactionChecker>} 收集。
     */
    @Bean
    public TransactionChecker orderPaidChecker(OrderMqHandlers handlers) {
        return handlers.orderPaidChecker();
    }

    @Bean
    public TransactionChecker refundSucceededChecker(OrderMqHandlers handlers) {
        return handlers.refundSucceededChecker();
    }

    @Bean
    public TransactionChecker orderCancelledChecker(OrderMqHandlers handlers) {
        return handlers.orderCancelledChecker();
    }

    /** 供生产侧注入的事件发布门面（生产 order.paid / refund.succeeded / order.cancelled）。 */
    public static List<String> producedTopics() {
        return List.of(MqTopics.ORDER_PAID, MqTopics.REFUND_SUCCEEDED, MqTopics.ORDER_CANCELLED);
    }
}
