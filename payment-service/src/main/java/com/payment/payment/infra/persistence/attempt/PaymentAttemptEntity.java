package com.payment.payment.infra.persistence.attempt;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

import java.time.Instant;

/**
 * 支付尝试持久化实体（PO）：承载 payment_attempts 表列，记录一次渠道交互的完整历史
 * （渠道身份、渠道引用、请求/响应时间、结果与状态）。状态机逻辑在 {@code domain.PaymentAttempt}。
 */
@TableName("payment_attempts")
public class PaymentAttemptEntity extends BaseEntity {

    private String paymentNo;
    private String channelCode;
    /** 尝试类型：PAYMENT / REFUND（Feature 016 / FR-017，退款渠道尝试复用本表）。 */
    private String attemptType = "PAYMENT";
    /** 本次渠道交互金额（最小货币单位，spec 018 / US1 / D2）。 */
    private Long amountMinor;
    /** 本次渠道交互币种（spec 018 / US1 / D2）。 */
    private String currencyCode;
    private Instant requestedAt;
    private Instant respondedAt;
    /** 渠道引用：数据库唯一约束兜底，重复回调映射到同一渠道交互。 */
    private String channelReference;
    /** 支付尝试状态机枚举名。 */
    private String status;
    private String failureReason;
    private Integer retryCount;
    /** 错误分类枚举名（TRANSIENT/HARD/UNKNOWN）：由双响应码派生，仅用于观测（ADR-0012/0013）。 */
    private String errorType;

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getAttemptType() {
        return attemptType;
    }

    public void setAttemptType(String attemptType) {
        this.attemptType = attemptType;
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

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }

    public String getChannelReference() {
        return channelReference;
    }

    public void setChannelReference(String channelReference) {
        this.channelReference = channelReference;
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

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }
}
