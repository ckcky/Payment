package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 额度操作流水持久化实体（PO）：承载 {@code limit_operations} 表列。
 *
 * <p><b>不继承 {@link com.payment.common.mybatis.BaseEntity}</b>：本表是纯幂等流水
 * （不可变、无状态迁移、无审计人/乐观锁语义），且其 {@code created_at} 需要精确到
 * {@code expires_at} 的同一时间基准。表列序守 ADR-0066 中「3 表豁免」的口径——
 * 流水表列序简化为 id → 业务主键 → 唯一索引列 → 其余。</p>
 */
@TableName("limit_operations")
public class LimitOperationEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationNo;
    private String bizNo;
    private String opType;
    private String userId;
    private String currencyCode;
    private String period;
    private Long amountMinor;
    private Instant expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOperationNo() {
        return operationNo;
    }

    public void setOperationNo(String operationNo) {
        this.operationNo = operationNo;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getOpType() {
        return opType;
    }

    public void setOpType(String opType) {
        this.opType = opType;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
