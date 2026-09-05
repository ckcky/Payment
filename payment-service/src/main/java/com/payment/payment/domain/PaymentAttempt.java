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

    /** 尝试类型（Feature 016 / FR-017）：支付尝试；退款尝试（复用本表，channel_reference=渠道退款流水号）。 */
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_REFUND = "REFUND";

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String paymentNo;
    private final String channelCode;
    /** 尝试类型：PAYMENT（默认）/ REFUND（退款渠道尝试，Feature 016）。 */
    private String attemptType = TYPE_PAYMENT;
    private Instant requestedAt;
    private Instant respondedAt;
    private String channelReference;
    private PaymentAttemptStatus status = PaymentAttemptStatus.PENDING;
    private String failureReason;
    private int retryCount;
    /**
     * 最后一次失败的错误分类（由双响应码派生，供观测排障；<b>不参与重试判定</b>，ADR-0012）。
     * 重试判定只看通信响应码 {@code TransportCode}。
     */
    private PaymentAttemptErrorType errorType;

    public PaymentAttempt(String paymentNo, String channelCode, int retryCount) {
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo");
        this.channelCode = Objects.requireNonNull(channelCode, "channelCode");
        this.requestedAt = Instant.now();
        this.retryCount = retryCount;
    }

    /** 退款渠道尝试（Feature 016 / FR-017 第②步）：复用 payment_attempts，channel_reference=渠道退款流水号。 */
    public static PaymentAttempt refundAttempt(String paymentNo, String channelCode) {
        PaymentAttempt attempt = new PaymentAttempt(paymentNo, channelCode, 0);
        attempt.attemptType = TYPE_REFUND;
        return attempt;
    }

    /**
     * 持久化重建：还原一次渠道交互的完整历史（引用/时间/状态/未知信息），绕过创建期状态机
     * （不改变业务规则）。
     */
    public static PaymentAttempt rehydrate(Long id, String paymentNo, String channelCode, int retryCount,
                                           Instant requestedAt, Instant respondedAt, String channelReference,
                                           PaymentAttemptStatus status, String failureReason,
                                           PaymentAttemptErrorType errorType,
                                           Integer version) {
        return rehydrate(id, paymentNo, channelCode, retryCount, requestedAt, respondedAt, channelReference,
                status, failureReason, errorType, version, TYPE_PAYMENT);
    }

    /** 全量重建（含尝试类型，Feature 016）。 */
    public static PaymentAttempt rehydrate(Long id, String paymentNo, String channelCode, int retryCount,
                                           Instant requestedAt, Instant respondedAt, String channelReference,
                                           PaymentAttemptStatus status, String failureReason,
                                           PaymentAttemptErrorType errorType,
                                           Integer version, String attemptType) {
        PaymentAttempt attempt = new PaymentAttempt(paymentNo, channelCode, retryCount);
        attempt.id = id;
        attempt.attemptType = attemptType == null ? TYPE_PAYMENT : attemptType;
        attempt.requestedAt = requestedAt;
        attempt.respondedAt = respondedAt;
        attempt.channelReference = channelReference;
        attempt.status = status;
        attempt.failureReason = failureReason;
        attempt.errorType = errorType;
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

    /**
     * ACCEPTED/UNKNOWN/PENDING → SUCCEEDED；终态冲突吸收（返回 false）。
     * PENDING 可收敛：收银台路径（ADR-0048 修订版）的尝试在取得渠道引用前即可能收到
     * 权威结果（人工裁定 / 迟到回调），此时尝试语义上仍"在途"，允许直接落终态。
     */
    public boolean succeed() {
        return transitionTo(PaymentAttemptStatus.SUCCEEDED, "succeed",
                PaymentAttemptStatus.PENDING, PaymentAttemptStatus.ACCEPTED, PaymentAttemptStatus.UNKNOWN);
    }

    /** ACCEPTED/UNKNOWN/PENDING → FAILED（权威收敛语义同 {@link #succeed()}）；终态冲突吸收（返回 false）。 */
    public boolean fail(String reason) {
        boolean changed = transitionTo(PaymentAttemptStatus.FAILED, "fail",
                PaymentAttemptStatus.PENDING, PaymentAttemptStatus.ACCEPTED, PaymentAttemptStatus.UNKNOWN);
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
        // 终态吸收迟到冲突结果（SUCCEEDED/FAILED 均不可被覆盖）；
        // PENDING 只能经权威结果收敛终态（见 succeed/fail 的 PENDING 来源态）。
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

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getChannelCode() {
        return channelCode;
    }

    /** 尝试类型：PAYMENT / REFUND（Feature 016）。 */
    public String getAttemptType() {
        return attemptType;
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

    /** 记录一次重试（ADR-0014）：重试序号自增，用于观测本次渠道调用实际重放了几轮。 */
    public void recordRetry() {
        this.retryCount++;
    }

    public PaymentAttemptErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(PaymentAttemptErrorType errorType) {
        this.errorType = errorType;
    }
}
