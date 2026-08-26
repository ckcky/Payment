package com.payment.payment.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.time.Instant;
import java.util.Objects;

/**
 * 支付尝试：记录一次渠道交互（渠道身份、渠道引用、请求/响应时间、结果与状态）。
 *
 * <p>每次尝试独立可追踪；重复回调映射到同一渠道引用；未知尝试在获得权威结果前保持未收敛。
 * 与 {@link Payment} 一致，终态（SUCCEEDED/FAILED）吸收迟到的冲突结果（返回 {@code false}），
 * 以支持乱序/重复回调的幂等处理。</p>
 */
public class PaymentAttempt {

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final Long paymentId;
    private final String channelCode;
    private Instant requestedAt;
    private Instant respondedAt;
    private String channelReference;
    private PaymentAttemptStatus status = PaymentAttemptStatus.PENDING;
    private String failureReason;
    private final int retryCount;

    public PaymentAttempt(Long paymentId, String channelCode, int retryCount) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.channelCode = Objects.requireNonNull(channelCode, "channelCode");
        this.requestedAt = Instant.now();
        this.retryCount = retryCount;
    }

    /**
     * 持久化重建：还原一次渠道交互的完整历史（引用/时间/状态/未知信息），绕过创建期状态机
     * （不改变业务规则）。
     */
    public static PaymentAttempt rehydrate(Long id, Long paymentId, String channelCode, int retryCount,
                                           Instant requestedAt, Instant respondedAt, String channelReference,
                                           PaymentAttemptStatus status, String failureReason, Integer version) {
        PaymentAttempt attempt = new PaymentAttempt(paymentId, channelCode, retryCount);
        attempt.id = id;
        attempt.requestedAt = requestedAt;
        attempt.respondedAt = respondedAt;
        attempt.channelReference = channelReference;
        attempt.status = status;
        attempt.failureReason = failureReason;
        attempt.version = version;
        return attempt;
    }

    // ---- 状态机 ----

    /** PENDING → ACCEPTED，记录渠道引用与响应时间；非 PENDING 时吸收（返回 false）。 */
    public boolean accept(String channelReference) {
        if (status != PaymentAttemptStatus.PENDING) {
            return false;
        }
        this.channelReference = channelReference;
        this.respondedAt = Instant.now();
        this.status = PaymentAttemptStatus.ACCEPTED;
        return true;
    }

    /** ACCEPTED/UNKNOWN → SUCCEEDED；终态冲突吸收（返回 false）。 */
    public boolean succeed() {
        return transitionTo(PaymentAttemptStatus.SUCCEEDED, "succeed",
                PaymentAttemptStatus.ACCEPTED, PaymentAttemptStatus.UNKNOWN);
    }

    /** ACCEPTED/UNKNOWN → FAILED；终态冲突吸收（返回 false）。 */
    public boolean fail(String reason) {
        boolean changed = transitionTo(PaymentAttemptStatus.FAILED, "fail",
                PaymentAttemptStatus.ACCEPTED, PaymentAttemptStatus.UNKNOWN);
        if (changed) {
            this.failureReason = reason;
        }
        return changed;
    }

    /** PENDING/ACCEPTED → UNKNOWN（超时/无响应）；终态冲突吸收（返回 false）。 */
    public boolean markUnknown(String reason) {
        if (status == PaymentAttemptStatus.UNKNOWN) {
            return false;
        }
        if (status == PaymentAttemptStatus.SUCCEEDED || status == PaymentAttemptStatus.FAILED) {
            return false; // 迟到未知结果，终态不覆盖
        }
        if (status != PaymentAttemptStatus.PENDING && status != PaymentAttemptStatus.ACCEPTED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal markUnknown from " + this.status);
        }
        this.status = PaymentAttemptStatus.UNKNOWN;
        this.failureReason = reason;
        return true;
    }

    private boolean transitionTo(PaymentAttemptStatus target, String op, PaymentAttemptStatus... from) {
        if (status == target) {
            return false;
        }
        for (PaymentAttemptStatus s : from) {
            if (status == s) {
                this.status = target;
                return true;
            }
        }
        // 终态吸收迟到冲突结果（SUCCEEDED/FAILED 均不可被覆盖）；PENDING 必须先 accept。
        if (status == PaymentAttemptStatus.SUCCEEDED || status == PaymentAttemptStatus.FAILED) {
            return false;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                "illegal " + op + " from " + this.status);
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getPaymentId() {
        return paymentId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public String getChannelReference() {
        return channelReference;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getRetryCount() {
        return retryCount;
    }
}
