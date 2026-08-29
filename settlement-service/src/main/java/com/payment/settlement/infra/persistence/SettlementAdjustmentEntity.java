package com.payment.settlement.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 结算调整项持久化实体（PO）：承载 settlement_adjustments 表列（ADR-0022 新增独立资金表）。
 * 领域规则在 {@code domain.SettlementAdjustment}，方向/状态以枚举名存储，映射由仓储完成。
 */
@TableName("settlement_adjustments")
public class SettlementAdjustmentEntity extends BaseEntity {

    /** 业务幂等键：独立唯一约束，杜绝并发重复登记。 */
    private String idempotencyKey;
    private String merchantId;
    private String period;
    /** 最小货币单位（BIGINT），恒 > 0（方向由 direction 表达）。 */
    private Long amountMinor;
    /** 方向枚举名：CREDIT / DEBIT。 */
    private String direction;
    private String currencyCode;
    private String reason;
    private String operator;
    /** 状态枚举名：ACTIVE / REVOKED。 */
    private String status;

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
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

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
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

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
