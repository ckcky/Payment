package com.payment.entitlement.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.time.LocalDateTime;

/**
 * 权益聚合根。
 *
 * <p>授予由「履约完成」事件触发，而非支付成功直接触发（Constitution 领域边界）。
 * 状态迁移只通过领域方法完成；非法迁移抛出 {@link BizException}。</p>
 */
public class Entitlement {

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String userId;
    private final String orderNo;
    private final String sourceFulfillmentId;
    private String grantRef;
    private int availableQuantity;
    private final String scope;
    private final LocalDateTime expiryAt;
    private EntitlementStatus status;

    public Entitlement(String userId, String orderNo, String sourceFulfillmentId,
                       int availableQuantity, String scope, LocalDateTime expiryAt) {
        this.userId = userId;
        this.orderNo = orderNo;
        this.sourceFulfillmentId = sourceFulfillmentId;
        this.availableQuantity = availableQuantity;
        this.scope = scope;
        this.expiryAt = expiryAt;
        this.status = EntitlementStatus.PENDING_GRANT;
    }

    /**
     * 持久化重建：还原权益聚合及其历史状态/剩余量，绕过创建期状态机（不改变业务规则）。
     * {@code grantRef} 为构造器之外的非派生字段，需显式回填。
     */
    public static Entitlement rehydrate(Long id, String userId, String orderNo, String sourceFulfillmentId,
                                        int availableQuantity, String scope, LocalDateTime expiryAt,
                                        EntitlementStatus status, Integer version, String grantRef) {
        Entitlement e = new Entitlement(userId, orderNo, sourceFulfillmentId, availableQuantity, scope, expiryAt);
        e.id = id;
        e.status = status;
        e.version = version;
        e.grantRef = grantRef;
        return e;
    }

    /** PENDING_GRANT → AVAILABLE。 */
    public void grant() {
        requireState(EntitlementStatus.PENDING_GRANT, "grant");
        this.status = EntitlementStatus.AVAILABLE;
    }

    /**
     * AVAILABLE → PARTIALLY_USED（剩余 &gt; 0）或 EXHAUSTED（剩余 == 0）。
     * 数量非法（&lt;= 0 或超过可用量）抛 {@link ErrorCodes#AMOUNT_INVARIANT_VIOLATION}。
     */
    public void consume(int qty) {
        if (qty <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "consume quantity must be positive, was " + qty);
        }
        requireAnyState("consume", EntitlementStatus.AVAILABLE, EntitlementStatus.PARTIALLY_USED);
        if (qty > this.availableQuantity) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "insufficient quantity: requested " + qty + ", available " + this.availableQuantity);
        }
        this.availableQuantity -= qty;
        this.status = this.availableQuantity > 0
                ? EntitlementStatus.PARTIALLY_USED
                : EntitlementStatus.EXHAUSTED;
    }

    /** AVAILABLE → EXPIRED。 */
    public void expire() {
        requireState(EntitlementStatus.AVAILABLE, "expire");
        this.status = EntitlementStatus.EXPIRED;
    }

    /** AVAILABLE → REVOKED。 */
    public void revoke() {
        requireState(EntitlementStatus.AVAILABLE, "revoke");
        this.status = EntitlementStatus.REVOKED;
    }

    /**
     * 退款驱动的权益撤销：幂等、不抛异常（区别于 {@link #revoke()}）。
     *
     * <p>仅撤销 {@code AVAILABLE} 权益；已消费/耗尽/过期/失败等非 AVAILABLE 状态
     * 不在此自动撤销，留待人工处理（退款成功但权益无法撤销时，保留退款事实，不伪造撤销成功）。</p>
     *
     * @return {@code true} 本次实际从 AVAILABLE 迁至 REVOKED；{@code false} 表示无需处理（已撤销或非 AVAILABLE）。
     */
    public boolean revokeForRefund() {
        if (this.status == EntitlementStatus.REVOKED) {
            return false;
        }
        if (this.status != EntitlementStatus.AVAILABLE) {
            return false;
        }
        this.status = EntitlementStatus.REVOKED;
        return true;
    }

    /** PENDING_GRANT → FAILED；{@code reason} 供失败事实与告警使用。 */
    public void fail(String reason) {
        requireState(EntitlementStatus.PENDING_GRANT, "fail");
        this.status = EntitlementStatus.FAILED;
    }

    private void requireState(EntitlementStatus expected, String action) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal transition: cannot " + action + " from " + this.status
                            + " (expected " + expected + ")");
        }
    }

    private void requireAnyState(String action, EntitlementStatus... allowed) {
        for (EntitlementStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                "illegal transition: cannot " + action + " from " + this.status);
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

    public String getUserId() {
        return userId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getSourceFulfillmentId() {
        return sourceFulfillmentId;
    }

    public String getGrantRef() {
        return grantRef;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public String getScope() {
        return scope;
    }

    public LocalDateTime getExpiryAt() {
        return expiryAt;
    }

    public EntitlementStatus getStatus() {
        return status;
    }
}
