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
    /** 渠道/收敛失败原因（终态 FAILED/REJECTED 回填，成功为 null）。 */
    private String failureReason;
    /** 幂等键（=TXRF，uk_transaction_refunds_idempotency_key）。 */
    private String idempotencyKey;
    /**
     * DB 维护的 {@code updated_at}（只读观测属性，spec 034 / T13）：搁浅判定（
     * 「最后写触碰」距今超阈值且仍 REQUESTED 未受理）的时间事实源，不新增列。
     * 内存新建值为 null（尚未持久化），调用方须容忍。
     */
    private java.time.Instant updatedAt;

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
        return rehydrate(id, refundNo, paymentRefundNo, transactionNo, orderNo, paymentNo, userId,
                amountMinor, currencyCode, status, reason, version, null);
    }

    /** 持久化重建（spec 034 / T13 扩展）：带 DB 维护的 updated_at（搁浅判定时间事实源，只读）。 */
    public static RefundOrder rehydrate(Long id, String refundNo, String paymentRefundNo, String transactionNo,
                                        String orderNo, String paymentNo, String userId, long amountMinor,
                                        String currencyCode, RefundOrderStatus status, String reason,
                                        Integer version, java.time.Instant updatedAt) {
        RefundOrder r = new RefundOrder(transactionNo, orderNo, paymentNo, userId,
                amountMinor, currencyCode, reason);
        r.id = id;
        r.refundNo = refundNo;
        r.idempotencyKey = refundNo;
        r.paymentRefundNo = paymentRefundNo;
        r.status = status;
        r.version = version;
        r.updatedAt = updatedAt;
        return r;
    }

    /** DB 维护的 updated_at（只读观测属性；内存新建为 null）。 */
    public java.time.Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 仓储落库时刻回填（spec 034 / T13：内存仓储用；MyBatis 路径由实体列映射，
     * 不经此方法）。只写空值——已持久化的行时间戳不因重放保存而前移。
     */
    public void markPersistedAt(java.time.Instant persistedAt) {
        if (this.updatedAt == null) {
            this.updatedAt = persistedAt;
        }
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
        return complete(terminal, paymentRefundNo, null);
    }

    /**
     * 渠道/收敛结果落终态（带失败原因）：终态吸收（重复或冲突结果不回退，返回 false 表示已吸收）。
     * 非成功终态时记录 {@code failureReason}（已在终态的重放不覆盖，幂等吸收语义）。
     */
    public boolean complete(RefundOrderStatus terminal, String paymentRefundNo, String failureReason) {
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
        if (failureReason != null && terminal != RefundOrderStatus.SUCCEEDED) {
            this.failureReason = failureReason;
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

    public String getFailureReason() {
        return failureReason;
    }

    /** 持久化重建专用（rehydrate 后回填），业务路径经 {@code complete} 写入。 */
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
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
