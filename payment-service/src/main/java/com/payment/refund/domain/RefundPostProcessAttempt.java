package com.payment.refund.domain;

import java.util.Objects;

/**
 * 退款后处理尝试记录（ADR-0017）：每次后处理目标（履约/权益/记账）的一次调用结果。
 *
 * <p>后处理失败不回滚退款成功事实（Saga），但 MUST 留下可追溯的尝试记录，便于运营按
 * {@code refundId} 查询并重放。终态不可变，仅追加。</p>
 */
public class RefundPostProcessAttempt {

    private Long id;
    /** 乐观锁并发令牌。 */
    private Integer version;
    private final Long refundId;
    /** 后处理目标：FULFILLMENT / ENTITLEMENT / LEDGER。 */
    private final String target;
    /** 结果：SUCCEEDED / FAILED。 */
    private final String status;
    /** 结果或错误摘要。 */
    private final String detail;
    /** 实际尝试次数（含重试）。 */
    private final int attemptCount;

    public RefundPostProcessAttempt(Long refundId, String target, String status, String detail, int attemptCount) {
        this.refundId = Objects.requireNonNull(refundId, "refundId");
        this.target = Objects.requireNonNull(target, "target");
        this.status = Objects.requireNonNull(status, "status");
        this.detail = detail;
        this.attemptCount = attemptCount;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public Long getRefundId() {
        return refundId;
    }

    public String getTarget() {
        return target;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public int getAttemptCount() {
        return attemptCount;
    }
}
