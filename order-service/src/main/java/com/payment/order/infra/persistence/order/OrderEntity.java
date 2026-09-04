package com.payment.order.infra.persistence.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 订单持久化实体（PO）：仅承载订单表列，领域规则在 {@code domain.Order}，映射由仓储完成。
 */
@TableName("orders")
public class OrderEntity extends BaseEntity {

    /** 业务单号（OR + 雪花，ADR-0062）。 */
    private String orderNo;
    private String userId;
    private String merchantId;
    /** 下游支付单号（payment-service 的 payment.id）。 */
    private Long paymentId;
    /** 订单状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private String currencyCode;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long totalMinor;
    private Long paidMinor;
    private Long refundedMinor;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public Long getTotalMinor() {
        return totalMinor;
    }

    public void setTotalMinor(Long totalMinor) {
        this.totalMinor = totalMinor;
    }

    public Long getPaidMinor() {
        return paidMinor;
    }

    public void setPaidMinor(Long paidMinor) {
        this.paidMinor = paidMinor;
    }

    public Long getRefundedMinor() {
        return refundedMinor;
    }

    public void setRefundedMinor(Long refundedMinor) {
        this.refundedMinor = refundedMinor;
    }
}
