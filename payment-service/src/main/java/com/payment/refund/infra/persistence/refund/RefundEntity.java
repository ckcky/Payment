package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 退款持久化实体（PO）：仅承载 refunds 表列，领域规则在 {@code domain.Refund}，映射由仓储完成。
 */
@TableName("refunds")
public class RefundEntity extends BaseEntity {

    /** 业务单号（PMRF + 雪花，ADR-0062/0067；存量 RF 保留不改写）。 */
    private String refundNo;
    /** 上层交易退款单号（TXRF，spec 019 双号互记；存量手工退款为 null）。 */
    private String transactionRefundNo;
    /** 所属交易单号（TX；spec 019 回调通知 order 时回传；存量数据为 null）。 */
    private String transactionNo;
    private String orderNo;
    private String paymentNo;
    private String userId;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    private String currencyCode;
    private String reason;
    /** 幂等键：数据库唯一约束兜底，杜绝并发重复退款。 */
    private String idempotencyKey;
    /** 退款状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private String failureReason;

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public String getTransactionRefundNo() {
        return transactionRefundNo;
    }

    public void setTransactionRefundNo(String transactionRefundNo) {
        this.transactionRefundNo = transactionRefundNo;
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
