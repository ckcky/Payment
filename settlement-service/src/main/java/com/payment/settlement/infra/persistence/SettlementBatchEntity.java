package com.payment.settlement.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 结算批次持久化实体（PO）：承载 settlement_batches 表列，领域规则在 {@code domain.SettlementBatch}，映射由仓储完成。
 */
@TableName("settlement_batches")
public class SettlementBatchEntity extends BaseEntity {

    private String merchantId;
    private String period;
    private String currencyCode;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long incomeMinor;
    private Long refundMinor;
    private Long adjustmentMinor;
    private Long netMinor;
    /** 结算状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;
    /** 幂等键：数据库唯一约束兜底，杜绝并发重复结算。 */
    private String idempotencyKey;

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

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public Long getIncomeMinor() {
        return incomeMinor;
    }

    public void setIncomeMinor(Long incomeMinor) {
        this.incomeMinor = incomeMinor;
    }

    public Long getRefundMinor() {
        return refundMinor;
    }

    public void setRefundMinor(Long refundMinor) {
        this.refundMinor = refundMinor;
    }

    public Long getAdjustmentMinor() {
        return adjustmentMinor;
    }

    public void setAdjustmentMinor(Long adjustmentMinor) {
        this.adjustmentMinor = adjustmentMinor;
    }

    public Long getNetMinor() {
        return netMinor;
    }

    public void setNetMinor(Long netMinor) {
        this.netMinor = netMinor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
