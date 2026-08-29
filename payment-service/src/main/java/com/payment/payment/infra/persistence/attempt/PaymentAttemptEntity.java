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

    private Long paymentId;
    private String channelCode;
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

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
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
