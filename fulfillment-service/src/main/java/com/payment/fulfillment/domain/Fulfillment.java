package com.payment.fulfillment.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * 履约聚合根（纯 POJO，无框架依赖）。
 *
 * <p>状态机：PENDING → PROCESSING → DELIVERED；PENDING → CANCELLED；PROCESSING → FAILED。
 * 状态只能通过领域方法推进，禁止外部直接 setStatus（Constitution 状态机铁律）。</p>
 *
 * <p>{@code sourcePaymentId} 是幂等键：同一支付成功事件只会创建一条履约。</p>
 */
public class Fulfillment {

    private Long id;
    private final String orderId;
    private final String orderItemId;
    private final String deliveryContent;
    private final String sourcePaymentId;
    private FulfillmentStatus status;
    private String failureReason;

    public Fulfillment(String orderId, String orderItemId, String deliveryContent, String sourcePaymentId) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.deliveryContent = deliveryContent;
        this.sourcePaymentId = sourcePaymentId;
        this.status = FulfillmentStatus.PENDING;
    }

    /** PENDING → PROCESSING。 */
    public void start() {
        requireStatus(FulfillmentStatus.PENDING, "start");
        this.status = FulfillmentStatus.PROCESSING;
    }

    /** PROCESSING → DELIVERED。 */
    public void deliver() {
        requireStatus(FulfillmentStatus.PROCESSING, "deliver");
        this.status = FulfillmentStatus.DELIVERED;
    }

    /** PROCESSING → FAILED。 */
    public void fail(String reason) {
        requireStatus(FulfillmentStatus.PROCESSING, "fail");
        this.status = FulfillmentStatus.FAILED;
        this.failureReason = reason;
    }

    /** PENDING → CANCELLED。 */
    public void cancel() {
        requireStatus(FulfillmentStatus.PENDING, "cancel");
        this.status = FulfillmentStatus.CANCELLED;
    }

    private void requireStatus(FulfillmentStatus expected, String action) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "Illegal fulfillment transition '" + action + "': expected " + expected
                            + " but was " + this.status);
        }
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public String getDeliveryContent() {
        return deliveryContent;
    }

    public String getSourcePaymentId() {
        return sourcePaymentId;
    }

    public FulfillmentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
