package com.payment.payment.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.Objects;

/**
 * 支付聚合：平台侧的支付意图与平台状态。
 *
 * <p>只保存平台支付意图（Transaction 引用、金额、币种、幂等键）与平台状态，不保存渠道内部状态。
 * 金额一律最小货币单位（long）。</p>
 *
 * <p>状态机关键不变量：终态成功不能被后到的失败回调覆盖；对称地，终态失败也不被后到的成功覆盖。
 * 终态（SUCCEEDED/FAILED/CLOSED）吸收一切迟到的冲突结果（返回 {@code false}，不触发事件），
 * 只有「真正发生了状态迁移」才返回 {@code true}（用于决定是否发布领域事件）。</p>
 */
public class Payment {

    private Long id;
    private final String transactionId;
    private final String orderId;
    private final String userId;
    private final long amountMinor;
    private final String currencyCode;
    private final String idempotencyKey;
    private PaymentStatus status = PaymentStatus.PENDING;
    private Long currentAttemptId;
    private String failureReason;

    public Payment(String transactionId, String orderId, String userId, long amountMinor,
                   String currencyCode, String idempotencyKey) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.userId = Objects.requireNonNull(userId, "userId");
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "payment amount must be > 0");
        }
        this.amountMinor = amountMinor;
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    // ---- 状态机（唯一状态变更入口）----

    /** PENDING → PROCESSING，记录当前尝试。 */
    public void start(Long attemptId) {
        requireStatus(PaymentStatus.PENDING, "start");
        this.currentAttemptId = Objects.requireNonNull(attemptId, "attemptId");
        this.status = PaymentStatus.PROCESSING;
    }

    /** PROCESSING/UNKNOWN → SUCCEEDED。终态冲突被吸收（返回 false）。 */
    public boolean succeed() {
        return transitionTo(PaymentStatus.SUCCEEDED, "succeed", PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN);
    }

    /** PROCESSING/UNKNOWN → FAILED。终态冲突被吸收（返回 false）。 */
    public boolean fail(String reason) {
        boolean changed = transitionTo(PaymentStatus.FAILED, "fail", PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN);
        if (changed) {
            this.failureReason = reason;
        }
        return changed;
    }

    /** PROCESSING → UNKNOWN。终态冲突被吸收（返回 false）。 */
    public boolean markUnknown(String reason) {
        boolean changed = transitionTo(PaymentStatus.UNKNOWN, "markUnknown", PaymentStatus.PROCESSING);
        if (changed) {
            this.failureReason = reason;
        }
        return changed;
    }

    /** SUCCEEDED/FAILED → CLOSED。 */
    public void close() {
        if (status == PaymentStatus.SUCCEEDED || status == PaymentStatus.FAILED) {
            this.status = PaymentStatus.CLOSED;
            return;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                "illegal close from " + this.status);
    }

    private boolean transitionTo(PaymentStatus target, String op, PaymentStatus... from) {
        if (status == target) {
            return false; // 幂等重复
        }
        for (PaymentStatus s : from) {
            if (status == s) {
                this.status = target;
                return true;
            }
        }
        // 终态（SUCCEEDED/FAILED/CLOSED）吸收迟到冲突结果；PENDING 必须 start 先行。
        if (isTerminal()) {
            return false;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                "illegal " + op + " from " + this.status);
    }

    private boolean isTerminal() {
        return status == PaymentStatus.SUCCEEDED
                || status == PaymentStatus.FAILED
                || status == PaymentStatus.CLOSED;
    }

    private void requireStatus(PaymentStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getOrderId() {
        return orderId;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Long getCurrentAttemptId() {
        return currentAttemptId;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
