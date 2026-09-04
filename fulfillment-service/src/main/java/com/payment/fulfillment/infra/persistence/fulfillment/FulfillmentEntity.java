package com.payment.fulfillment.infra.persistence.fulfillment;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 履约持久化实体（PO）：仅承载履约表列，领域规则在 {@code domain.Fulfillment}，映射由仓储完成。
 */
@TableName("fulfillments")
public class FulfillmentEntity extends BaseEntity {

    private String orderNo;
    private String orderItemId;
    private String deliveryContent;
    /** 支付幂等键（同源支付事件只创建一条履约）。 */
    private String sourcePaymentNo;
    /** 履约状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private String failureReason;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(String orderItemId) {
        this.orderItemId = orderItemId;
    }

    public String getDeliveryContent() {
        return deliveryContent;
    }

    public void setDeliveryContent(String deliveryContent) {
        this.deliveryContent = deliveryContent;
    }

    public String getSourcePaymentNo() {
        return sourcePaymentNo;
    }

    public void setSourcePaymentNo(String sourcePaymentNo) {
        this.sourcePaymentNo = sourcePaymentNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
