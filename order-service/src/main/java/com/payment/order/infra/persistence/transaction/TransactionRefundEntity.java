package com.payment.order.infra.persistence.transaction;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 交易层退款单持久化实体（PO，spec 019 / ADR-0067）：承载 transaction_refunds 表列，
 * 状态机逻辑在 {@code domain.RefundOrder}。
 */
@TableName("transaction_refunds")
public class TransactionRefundEntity extends BaseEntity {

    /** 交易层退款单号（TXRF + 雪花），业务主键 + 幂等键。 */
    private String refundNo;
    /** 支付层退款执行单号（PMRF），payment 响应回填。 */
    private String paymentRefundNo;
    private String transactionNo;
    private String orderNo;
    private String paymentNo;
    private String userId;
    private Long amountMinor;
    private String currencyCode;
    private String status;
    private String reason;
    /** 幂等键 = TXRF。 */
    private String idempotencyKey;

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public String getPaymentRefundNo() {
        return paymentRefundNo;
    }

    public void setPaymentRefundNo(String paymentRefundNo) {
        this.paymentRefundNo = paymentRefundNo;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public void setTransactionNo(String transactionNo) {
        this.transactionNo = transactionNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
