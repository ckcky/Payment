package com.payment.order.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.Objects;

/**
 * 交易聚合：一次商业交易与订单、支付的关联及交易生命周期（MVP：Order 1:1 Transaction）。
 *
 * <p>不取代订单或支付；未知状态只能由权威结果收敛，不可猜测成败。</p>
 */
public class Transaction {

    private Long id;
    private final String orderId;
    private final long amountMinor;
    private final String currencyCode;
    private final String purpose;
    private TransactionStatus status = TransactionStatus.PENDING;

    public Transaction(String orderId, long amountMinor, String currencyCode, String purpose) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "transaction amount must be > 0");
        }
        this.amountMinor = amountMinor;
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.purpose = purpose == null ? "PURCHASE" : purpose;
    }

    public void start() {
        requireStatus(TransactionStatus.PENDING, "start");
        this.status = TransactionStatus.PROCESSING;
    }

    public boolean succeed() {
        return transitionToSucceeded();
    }

    public boolean fail() {
        return transitionToFailed();
    }

    public boolean markUnknown() {
        if (this.status == TransactionStatus.UNKNOWN) {
            return false;
        }
        requireStatus(TransactionStatus.PROCESSING, "markUnknown");
        this.status = TransactionStatus.UNKNOWN;
        return true;
    }

    public void cancel() {
        requireStatus(TransactionStatus.PENDING, "cancel");
        this.status = TransactionStatus.CANCELLED;
    }

    private boolean transitionToSucceeded() {
        if (this.status == TransactionStatus.SUCCEEDED) {
            return false;
        }
        requireAnyStatus("succeed", TransactionStatus.PROCESSING, TransactionStatus.UNKNOWN);
        this.status = TransactionStatus.SUCCEEDED;
        return true;
    }

    private boolean transitionToFailed() {
        if (this.status == TransactionStatus.FAILED) {
            return false;
        }
        requireAnyStatus("fail", TransactionStatus.PROCESSING, TransactionStatus.UNKNOWN);
        this.status = TransactionStatus.FAILED;
        return true;
    }

    private void requireStatus(TransactionStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    private void requireAnyStatus(String op, TransactionStatus... allowed) {
        for (TransactionStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal " + op + " from " + this.status);
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getPurpose() {
        return purpose;
    }

    public TransactionStatus getStatus() {
        return status;
    }
}
