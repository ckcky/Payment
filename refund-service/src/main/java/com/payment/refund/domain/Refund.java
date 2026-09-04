package com.payment.refund.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.List;
import java.util.Objects;

/**
 * 退款聚合根：一次退款申请及其生命周期。
 *
 * <p>只保存退款申请事实（金额、币种、原因、关联订单/支付、幂等键）与退款状态，
 * 不直接修改 Payment/Fulfillment/Entitlement 状态。金额一律最小货币单位（long）。</p>
 *
 * <p>状态机不变量：终态（SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED/REJECTED/CLOSED）吸收迟到冲突结果；
 * UNKNOWN 只经权威结果收敛为成功/失败，不得臆断。累计退款约束由 {@link RefundPolicy} 在应用层判定。</p>
 */
public class Refund {

    private Long id;
    /** 业务单号（RF + 雪花，ADR-0062）。 */
    private String refundNo;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String orderId;
    private final Long paymentId;
    private final String userId;
    private final long amountMinor;
    private final String currencyCode;
    private final String reason;
    private final String idempotencyKey;
    private final List<RefundItem> items;
    private RefundStatus status = RefundStatus.REQUESTED;
    private String failureReason;

    public Refund(String orderId, Long paymentId, String userId, long amountMinor,
                  String currencyCode, String reason, String idempotencyKey, List<RefundItem> items) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.userId = Objects.requireNonNull(userId, "userId");
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "refund amount must be > 0");
        }
        this.amountMinor = amountMinor;
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.items = List.copyOf(items == null ? List.of() : items);
        this.refundNo = BusinessNos.of(BusinessNoType.REFUND);
    }

    /**
     * 持久化重建：还原退款聚合及其历史状态，绕过创建期校验（不改变业务规则）。
     */
    public static Refund rehydrate(Long id, String refundNo, String orderId, Long paymentId, String userId,
                                   long amountMinor, String currencyCode, String reason,
                                   String idempotencyKey, List<RefundItem> items,
                                   RefundStatus status, String failureReason, Integer version) {
        Refund refund = new Refund(orderId, paymentId, userId, amountMinor, currencyCode,
                reason, idempotencyKey, items);
        refund.id = id;
        refund.refundNo = refundNo;
        refund.status = status;
        refund.failureReason = failureReason;
        refund.version = version;
        return refund;
    }

    // ---- 状态机（唯一状态变更入口）----

    /** REQUESTED → PROCESSING（发起渠道退款尝试）。 */
    public void process() {
        requireStatus(RefundStatus.REQUESTED, "process");
        this.status = RefundStatus.PROCESSING;
    }

    /** REQUESTED → REJECTED（资格校验不通过）。 */
    public void reject(String reason) {
        requireStatus(RefundStatus.REQUESTED, "reject");
        this.status = RefundStatus.REJECTED;
        this.failureReason = reason;
    }

    /** PROCESSING/UNKNOWN → SUCCEEDED（全额退款）；终态冲突被吸收（返回 false）。 */
    public boolean succeed() {
        return transitionTo(RefundStatus.SUCCEEDED, "succeed", RefundStatus.PROCESSING, RefundStatus.UNKNOWN);
    }

    /**
     * PROCESSING/UNKNOWN → PARTIALLY_SUCCEEDED（部分退款）；终态冲突被吸收（返回 false）。
     *
     * <p><b>ADR-0016 已否决</b>：负责人决议「部分退款不做」，本方法当前<b>无调用方</b>，
     * 保留仅为维持状态机枚举完整性（与 ADR-0016 之前的基线一致：已实现但不可达）。</p>
     */
    public boolean partiallySucceed(long refundedAmount) {
        if (refundedAmount <= 0 || refundedAmount >= this.amountMinor) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "partial refund amount must be in (0, amountMinor): " + refundedAmount);
        }
        return transitionTo(RefundStatus.PARTIALLY_SUCCEEDED, "partiallySucceed",
                RefundStatus.PROCESSING, RefundStatus.UNKNOWN);
    }

    /** PROCESSING/UNKNOWN → FAILED；终态冲突被吸收（返回 false）。 */
    public boolean fail(String reason) {
        boolean changed = transitionTo(RefundStatus.FAILED, "fail", RefundStatus.PROCESSING, RefundStatus.UNKNOWN);
        if (changed) {
            this.failureReason = reason;
        }
        return changed;
    }

    /** PROCESSING → UNKNOWN（渠道结果未知）；终态冲突被吸收（返回 false）。 */
    public boolean markUnknown(String reason) {
        boolean changed = transitionTo(RefundStatus.UNKNOWN, "markUnknown", RefundStatus.PROCESSING);
        if (changed) {
            this.failureReason = reason;
        }
        return changed;
    }

    /** 成功/部分成功/失败/拒绝 → CLOSED。 */
    public void close() {
        if (status == RefundStatus.SUCCEEDED || status == RefundStatus.PARTIALLY_SUCCEEDED
                || status == RefundStatus.FAILED || status == RefundStatus.REJECTED) {
            this.status = RefundStatus.CLOSED;
            return;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal close from " + this.status);
    }

    private boolean transitionTo(RefundStatus target, String op, RefundStatus... from) {
        if (status == target) {
            return false; // 幂等重复
        }
        for (RefundStatus s : from) {
            if (status == s) {
                this.status = target;
                return true;
            }
        }
        if (isTerminal()) {
            return false; // 终态吸收迟到冲突结果
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal " + op + " from " + this.status);
    }

    private boolean isTerminal() {
        return status == RefundStatus.SUCCEEDED || status == RefundStatus.PARTIALLY_SUCCEEDED
                || status == RefundStatus.FAILED || status == RefundStatus.REJECTED
                || status == RefundStatus.CLOSED;
    }

    private void requireStatus(RefundStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRefundNo() {
        return refundNo;
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

    public Long getPaymentId() {
        return paymentId;
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

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public List<RefundItem> getItems() {
        return items;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
