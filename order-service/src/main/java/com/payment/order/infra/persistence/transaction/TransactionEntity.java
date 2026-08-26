package com.payment.order.infra.persistence.transaction;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 交易持久化实体（PO）：承载交易表列，状态机逻辑在 {@code domain.Transaction}。
 */
@TableName("transactions")
public class TransactionEntity extends BaseEntity {

    private String orderId;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    private String currencyCode;
    private String purpose;
    /** 交易状态机枚举名。 */
    private String status;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
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
}
