package com.payment.payment.infra.persistence.payment;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 支付持久化实体（PO）：仅承载 payments 表列，领域规则在 {@code domain.Payment}，映射由仓储完成。
 */
@TableName("payments")
public class PaymentEntity extends BaseEntity {

    private String transactionId;
    private String orderId;
    private String userId;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    private String currencyCode;
    /** 幂等键：数据库唯一约束兜底，杜绝并发重复扣款。 */
    private String idempotencyKey;
    /** 支付状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private Long currentAttemptId;
    private String failureReason;

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCurrentAttemptId() {
        return currentAttemptId;
    }

    public void setCurrentAttemptId(Long currentAttemptId) {
        this.currentAttemptId = currentAttemptId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
