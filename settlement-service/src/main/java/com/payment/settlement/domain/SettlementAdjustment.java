package com.payment.settlement.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 结算调整项（ADR-0022）：先于批次登记、独立建表、独立幂等键与撤销状态。
 *
 * <p>金额 {@code amountMinor} 恒 &gt; 0；方向由 {@link AdjustmentDirection} 表达。
 * 带符号金额 {@link #signedAmountMinor()}：CREDIT 取正（增加净额）、DEBIT 取负（减少净额）。</p>
 */
public class SettlementAdjustment {

    private Long id;
    private Integer version;
    private final String idempotencyKey;
    private final String merchantId;
    private final String period;
    private final long amountMinor;
    private final AdjustmentDirection direction;
    private final String currencyCode;
    private final String reason;
    private final String operator;
    private AdjustmentStatus status = AdjustmentStatus.ACTIVE;
    private LocalDateTime createdAt;

    public SettlementAdjustment(String idempotencyKey, String merchantId, String period, long amountMinor,
                                AdjustmentDirection direction, String currencyCode, String reason, String operator) {
        this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
        this.merchantId = requireNonBlank(merchantId, "merchantId");
        this.period = requireNonBlank(period, "period");
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "adjustment amount must be > 0");
        }
        this.amountMinor = amountMinor;
        this.direction = Objects.requireNonNull(direction, "direction");
        this.currencyCode = requireNonBlank(currencyCode, "currencyCode");
        this.reason = requireNonBlank(reason, "reason");
        this.operator = requireNonBlank(operator, "operator");
        this.createdAt = LocalDateTime.now();
    }

    /** 持久化重建：绕过创建期校验（不改变业务规则）。 */
    public static SettlementAdjustment rehydrate(Long id, Integer version, String idempotencyKey, String merchantId,
                                                 String period, long amountMinor, AdjustmentDirection direction,
                                                 String currencyCode, String reason, String operator,
                                                 AdjustmentStatus status, LocalDateTime createdAt) {
        SettlementAdjustment a = new SettlementAdjustment(idempotencyKey, merchantId, period, amountMinor,
                direction, currencyCode, reason, operator);
        a.id = id;
        a.version = version;
        a.status = status;
        a.createdAt = createdAt;
        return a;
    }

    /** 带符号金额：CREDIT 取正、DEBIT 取负（净额公式口径）。 */
    public long signedAmountMinor() {
        return direction == AdjustmentDirection.CREDIT ? amountMinor : -amountMinor;
    }

    /** 撤销：REVOKED 后不参与后续计算；幂等空操作。 */
    public void revoke() {
        if (status == AdjustmentStatus.REVOKED) {
            return;
        }
        this.status = AdjustmentStatus.REVOKED;
    }

    private static String requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, name + " must not be blank");
        }
        return v;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getPeriod() {
        return period;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public AdjustmentDirection getDirection() {
        return direction;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getReason() {
        return reason;
    }

    public String getOperator() {
        return operator;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
