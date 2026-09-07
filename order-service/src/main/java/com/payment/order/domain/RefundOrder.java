package com.payment.order.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.Objects;

/**
 * 交易层退款单聚合（spec 019 / ADR-0067）：order 驱动两层退款单的上层单（TXRF）。
 *
 * <p>状态机 REQUESTED → PROCESSING → 终态（{@link RefundOrderStatus#isTerminal()}），
 * 终态吸收 + 乐观锁。幂等键 = refundNo（TXRF）：同号重试由仓储按幂等键寻址回放，
 * 不重复向 payment 发起退款命令。</p>
 *
 * <p>双号互记：payment 受理后返回支付层执行单号（PMRF），回填到
 * {@code paymentRefundNo}（{@code transaction_refunds.payment_refund_no}）。</p>
 */
public class RefundOrder {

    private Long id;
    /** 业务单号（TXRF + 雪花，ADR-0062/0067）：本单业务主键，同时是幂等键。 */
    private String refundNo;
    /** 乐观锁并发令牌：由仓储读写。 */
    private Integer version;
    /** 支付层退款执行单号（PMRF+雪花）：payment 响应回填，双号互记。 */
    private String paymentRefundNo;
    private final String transactionNo;
    private final String orderNo;
    /** 被退支付单（PM）：surplus 场景为 surplus 支付单，正常退款为生效支付单。 */
    private final String paymentNo;
    private final String userId;
    private final long amountMinor;
    private final String currencyCode;
    private RefundOrderStatus status;
    private final String reason;
    /** 幂等键（=TXRF，uk_transaction_refunds_idempotency_key）。 */
    private String idempotencyKey;

    public RefundOrder(String transactionNo, String orderNo, String paymentNo, String userId,
                       long amountMinor, String currencyCode, String reason) {
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "refund amount must be > 0");
        }
        this.transactionNo = Objects.requireNonNull(transactionNo, "transactionNo");
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo");
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.amountMinor = amountMinor;
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.reason = reason == null ? "MANUAL_REFUND" : reason;
        this.refundNo = BusinessNos.of(BusinessNoType.TRANSACTION_REFUND);
        this.idempotencyKey = this.refundNo;
        this.status = RefundOrderStatus.REQUESTED;
    }

    /** 持久化重建：还原退款单聚合及历史状态，绕过创建期状态机。 */
    public static RefundOrder rehydrate(Long id, String refundNo, String paymentRefundNo, String transactionNo,
                                        String orderNo, String paymentNo, String userId, long amountMinor,
                                        String currencyCode, RefundOrderStatus status, String reason,
                                        Integer version) {
        RefundOrder r = new RefundOrder(transactionNo, orderNo, paymentNo, userId,
                amountMinor, currencyCode, reason);
        r.id = id;
        r.refundNo = refundNo;
        r.idempotencyKey = refundNo;
        r.paymentRefundNo = paymentRefundNo;
        r.status = status;
        r.version = version;
        return r;
    }

    /** payment 受理成功：回填 PMRF（双号互记）并推进到 PROCESSING。REQUESTED 之外的状态不适用。 */
    public void accept(String paymentRefundNo) {
        if (this.status != RefundOrderStatus.REQUESTED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal refund accept from " + this.status);
        }
        this.paymentRefundNo = Objects.requireNonNull(paymentRefundNo, "paymentRefundNo");
        this.status = RefundOrderStatus.PROCESSING;
    }

    /** 渠道/收敛结果落终态：终态吸收（重复或冲突结果不回退，返回 false 表示已吸收）。 */
    public boolean complete(RefundOrderStatus terminal, String paymentRefundNo) {
        if (!terminal.isTerminal()) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "refund complete requires terminal status: " + terminal);
        }
        if (this.status.isTerminal()) {
            return false; // 终态吸收：重复通知幂等
        }
        if (paymentRefundNo != null && this.paymentRefundNo == null) {
            this.paymentRefundNo = paymentRefundNo;
        }
        this.status = terminal;
        return true;
    }

    /** 是否为生效支付单的退款（surplus 被退单不在 order/transaction 账上累加）。 */
    public boolean refundsEffectivePayment(Order order) {
        return paymentNo.equals(order.getPaymentNo());
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public String getPaymentRefundNo() {
        return paymentRefundNo;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public RefundOrderStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
