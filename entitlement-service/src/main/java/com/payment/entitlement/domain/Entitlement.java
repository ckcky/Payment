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
    private final String userId;
    private final String orderId;
    private final String sourceFulfillmentId;
    private String grantRef;
    private int availableQuantity;
    private final String scope;
    private final LocalDateTime expiryAt;
    private EntitlementStatus status;

    public Entitlement(String userId, String orderId, String sourceFulfillmentId,
                       int availableQuantity, String scope, LocalDateTime expiryAt) {
        this.userId = userId;
        this.orderId = orderId;
        this.sourceFulfillmentId = sourceFulfillmentId;
        this.availableQuantity = availableQuantity;
        this.scope = scope;
        this.expiryAt = expiryAt;
        this.status = EntitlementStatus.PENDING_GRANT;
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

    public String getUserId() {
        return userId;
    }

    public String getOrderId() {
        return orderId;
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
