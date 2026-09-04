package com.payment.entitlement.infra.persistence.entitlement;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

import java.time.LocalDateTime;

/**
 * 权益持久化实体（PO）：仅承载权益表列，领域规则在 {@code domain.Entitlement}，映射由仓储完成。
 */
@TableName("entitlements")
public class EntitlementEntity extends BaseEntity {

    private String userId;
    private String orderNo;
    /** 幂等键：同一履约完成请求只授予一次（表上唯一索引）。 */
    private String sourceFulfillmentId;
    private String grantRef;
    private Integer availableQuantity;
    private String scope;
    private LocalDateTime expiryAt;
    /** 权益状态机枚举名（状态机逻辑在领域层，持久化只存枚举名）。 */
    private String status;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getSourceFulfillmentId() {
        return sourceFulfillmentId;
    }

    public void setSourceFulfillmentId(String sourceFulfillmentId) {
        this.sourceFulfillmentId = sourceFulfillmentId;
    }

    public String getGrantRef() {
        return grantRef;
    }

    public void setGrantRef(String grantRef) {
        this.grantRef = grantRef;
    }

    public Integer getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(Integer availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public LocalDateTime getExpiryAt() {
        return expiryAt;
    }

    public void setExpiryAt(LocalDateTime expiryAt) {
        this.expiryAt = expiryAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
