package com.payment.fulfillment.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * 履约聚合根（纯 POJO，无框架依赖）。
 *
 * <p>状态机：PENDING → PROCESSING → DELIVERED；PENDING → CANCELLED；PROCESSING → FAILED。
 * 状态只能通过领域方法推进，禁止外部直接 setStatus（Constitution 状态机铁律）。</p>
 *
 * <p>{@code sourcePaymentNo} 是幂等键：同一支付成功事件只会创建一条履约。</p>
 */
public class Fulfillment {

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String orderNo;
    private final String orderItemId;
    private final String deliveryContent;
    private final String sourcePaymentNo;
    private FulfillmentStatus status;
    private String failureReason;

    public Fulfillment(String orderNo, String orderItemId, String deliveryContent, String sourcePaymentNo) {
        this.orderNo = orderNo;
        this.orderItemId = orderItemId;
        this.deliveryContent = deliveryContent;
        this.sourcePaymentNo = sourcePaymentNo;
        this.status = FulfillmentStatus.PENDING;
    }

    /**
     * 持久化重建：还原履约聚合及其历史状态，绕过创建期状态机（不改变业务规则）。
     */
    public static Fulfillment rehydrate(Long id, String orderNo, String orderItemId, String deliveryContent,
                                        String sourcePaymentNo, FulfillmentStatus status, String failureReason,
                                        Integer version) {
        Fulfillment fulfillment = new Fulfillment(orderNo, orderItemId, deliveryContent, sourcePaymentNo);
        fulfillment.id = id;
        fulfillment.status = status;
        fulfillment.failureReason = failureReason;
        fulfillment.version = version;
        return fulfillment;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public String getDeliveryContent() {
        return deliveryContent;
    }

    public String getSourcePaymentNo() {
        return sourcePaymentNo;
    }

    public FulfillmentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
