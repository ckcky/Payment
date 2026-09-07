package com.payment.order.infra.persistence.transaction;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 交易持久化实体（PO）：承载交易表列，状态机逻辑在 {@code domain.Transaction}。
 */
@TableName("transactions")
public class TransactionEntity extends BaseEntity {

    /** 业务单号（TX + 雪花，ADR-0062）。 */
    private String transactionNo;
    private String orderNo;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    private String currencyCode;
    private String purpose;
    /** 交易状态机枚举名。 */
    private String status;
    /** 生效支付单：首张成功支付（spec 019）。 */
    private String paymentNo;
    /** 累计已退金额（spec 019）。 */
    private Long refundedMinor;

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

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
    }

    public Long getRefundedMinor() {
        return refundedMinor;
    }

    public void setRefundedMinor(Long refundedMinor) {
        this.refundedMinor = refundedMinor;
    }
}
