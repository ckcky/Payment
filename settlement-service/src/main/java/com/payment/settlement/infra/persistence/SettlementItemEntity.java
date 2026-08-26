package com.payment.settlement.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 结算明细持久化实体（PO）：承载 settlement_items 表列（结算批次的 1:N 明细值对象）。
 */
@TableName("settlement_items")
public class SettlementItemEntity extends BaseEntity {

    private Long batchId;
    private String reference;
    /** PAYMENT / REFUND / ADJUSTMENT。 */
    private String type;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;
    private String currencyCode;

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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
}
