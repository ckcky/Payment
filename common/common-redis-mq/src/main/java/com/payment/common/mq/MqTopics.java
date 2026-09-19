package com.payment.common.mq;

/**
 * 事件主题（topic）常量（spec 029 / FR-200 系列）。
 *
 * <p>生产方与订阅方共用同一组常量，避免字符串字面量漂移导致「发了没人收」。</p>
 */
public final class MqTopics {

    /** payment → order（点对点）：支付成功。替代 {@code OrderGateway.notifyPaymentSucceeded}。 */
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";

    /** payment → order（点对点）：退款终态结果。替代 {@code OrderGateway.notifyRefundResult}。 */
    public static final String REFUND_RESULT = "refund.result";

    /** order → catalog/fulfillment/trace（广播）：订单已支付。替代 confirmStock + 履约驱动。 */
    public static final String ORDER_PAID = "order.paid";

    /** order → catalog/fulfillment/trace（广播）：退款成功。替代秒杀回补 + 履约终止。 */
    public static final String REFUND_SUCCEEDED = "refund.succeeded";

    /** order → catalog/payment/fulfillment/trace（广播）：订单已取消（新增能力）。 */
    public static final String ORDER_CANCELLED = "order.cancelled";

    /** fulfillment → entitlement（点对点）：履约完成。替代 {@code EntitlementGateway.grant}。 */
    public static final String FULFILLMENT_COMPLETED = "fulfillment.completed";

    /** fulfillment → entitlement（点对点）：履约撤销。替代 {@code EntitlementGateway.revokeOnRefund}。 */
    public static final String FULFILLMENT_REVOKED = "fulfillment.revoked";

    /** 全部 topic（trace 消费组订阅用，FR-402）。 */
    public static final String[] ALL = {
            PAYMENT_SUCCEEDED, REFUND_RESULT, ORDER_PAID, REFUND_SUCCEEDED,
            ORDER_CANCELLED, FULFILLMENT_COMPLETED, FULFILLMENT_REVOKED
    };

    private MqTopics() {
    }
}
