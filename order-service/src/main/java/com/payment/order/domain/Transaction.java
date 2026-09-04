package com.payment.order.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.Objects;

/**
 * 交易聚合：一次商业交易与订单、支付的关联及交易生命周期（MVP：Order 1:1 Transaction）。
 *
 * <p>不取代订单或支付；未知状态只能由权威结果收敛，不可猜测成败。</p>
 */
public class Transaction {

    private Long id;
    /** 业务单号（TX + 雪花，ADR-0062）：跨服务支付意图引用此单号（payments.transaction_id）。 */
    private String transactionNo;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
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
        this.transactionNo = BusinessNos.of(BusinessNoType.TRANSACTION);
    }

    /** 持久化重建：还原交易聚合及其历史状态，绕过创建期状态机（不改变业务规则）。 */
    public static Transaction rehydrate(Long id, String transactionNo, String orderId, long amountMinor, String currencyCode,
                                        String purpose, TransactionStatus status, Integer version) {
        Transaction t = new Transaction(orderId, amountMinor, currencyCode, purpose);
        t.id = id;
        t.transactionNo = transactionNo;
        t.status = status;
        t.version = version;
        return t;
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

    public String getTransactionNo() {
        return transactionNo;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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
