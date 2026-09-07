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
    private final String orderNo;
    private final long amountMinor;
    private final String currencyCode;
    private final String purpose;
    private TransactionStatus status = TransactionStatus.PENDING;
    /** 生效支付单：首张成功支付（spec 019 / ADR-0067；surplus 被退单不覆盖此列）。 */
    private String paymentNo;
    /** 累计已退金额（spec 019 / ADR-0067）：SUCCEEDED 退款回调累加，按 TXRF 幂等。 */
    private long refundedMinor = 0L;

    public Transaction(String orderNo, long amountMinor, String currencyCode, String purpose) {
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo");
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
                                        String purpose, TransactionStatus status, Integer version,
                                        String paymentNo, long refundedMinor) {
        Transaction t = new Transaction(orderId, amountMinor, currencyCode, purpose);
        t.id = id;
        t.transactionNo = transactionNo;
        t.status = status;
        t.version = version;
        t.paymentNo = paymentNo;
        t.refundedMinor = refundedMinor;
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

    /**
     * 记录生效支付单（spec 019）：仅首张成功支付写入（null 时写入），重复/surplus 通知不覆盖。
     * @return true 表示本次写入生效。
     */
    public boolean recordEffectivePayment(String paymentNo) {
        if (this.paymentNo != null) {
            return false;
        }
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo");
        return true;
    }

    /** 累加已退金额（spec 019）：按 TXRF 幂等由调用方保证（仅 SUCCEEDED 首次迁移时调用）。 */
    public void accumulateRefund(long amountMinor) {
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "refund amount must be > 0");
        }
        this.refundedMinor = Math.addExact(this.refundedMinor, amountMinor);
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

    public String getOrderNo() {
        return orderNo;
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

    public String getPaymentNo() {
        return paymentNo;
    }

    public long getRefundedMinor() {
        return refundedMinor;
    }
}
