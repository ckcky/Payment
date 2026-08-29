package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 退款持久化实体（PO）：仅承载 refunds 表列，领域规则在 {@code domain.Refund}，映射由仓储完成。
 */
@TableName("refunds")
public class RefundEntity extends BaseEntity {

    private String orderId;
    private Long paymentId;
    private String userId;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    /** 已确认退款金额（最小货币单位）；部分退款时小于 amountMinor。 */
    private Long refundedAmountMinor;
    private String currencyCode;
    private String reason;
    /** 幂等键：数据库唯一约束兜底，杜绝并发重复退款。 */
    private String idempotencyKey;
    /** 退款状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private String failureReason;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
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

    public Long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public void setRefundedAmountMinor(Long refundedAmountMinor) {
        this.refundedAmountMinor = refundedAmountMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
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
