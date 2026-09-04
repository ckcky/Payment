package com.payment.payment.infra.persistence.payment;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;
import java.time.Instant;

/**
 * 支付持久化实体（PO）：仅承载 payments 表列，领域规则在 {@code domain.Payment}，映射由仓储完成。
 */
@TableName("payments")
public class PaymentEntity extends BaseEntity {

    /** 业务单号（PM + 雪花，ADR-0062）。 */
    private String paymentNo;
    private String transactionId;
    private String orderNo;
    private String userId;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    private String currencyCode;
    /** 幂等键：数据库唯一约束兜底，杜绝并发重复扣款。 */
    private String idempotencyKey;
    /** 同一交易内支付尝试序号（1 起），一交易多支付单时区分各支付单（Feature 015）。 */
    private int attemptSeq = 1;
    /** 支付状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    private Long currentAttemptId;
    private String failureReason;
    /** UNKNOWN 主动查询累计次数（spec US2 / ADR-0003）。 */
    private Integer queryAttempts;
    /** 进入 UNKNOWN 的时刻（spec US5 / ADR-0015），用于计算真实收敛时长。 */
    private Instant enteredUnknownAt;

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

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

    public int getAttemptSeq() {
        return attemptSeq;
    }

    public void setAttemptSeq(int attemptSeq) {
        this.attemptSeq = attemptSeq;
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

    public Integer getQueryAttempts() {
        return queryAttempts;
    }

    public void setQueryAttempts(Integer queryAttempts) {
        this.queryAttempts = queryAttempts;
    }

    public Instant getEnteredUnknownAt() {
        return enteredUnknownAt;
    }

    public void setEnteredUnknownAt(Instant enteredUnknownAt) {
        this.enteredUnknownAt = enteredUnknownAt;
    }
}
