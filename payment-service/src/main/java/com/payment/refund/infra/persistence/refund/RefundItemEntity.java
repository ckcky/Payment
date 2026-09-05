package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 退款明细持久化实体（PO）：承载 refund_items 表列（退款聚合的 1:N 明细值对象）。
 */
@TableName("refund_items")
public class RefundItemEntity extends BaseEntity {

    private String refundNo;
    private String orderItemId;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long amountMinor;

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(String orderItemId) {
        this.orderItemId = orderItemId;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }
}
