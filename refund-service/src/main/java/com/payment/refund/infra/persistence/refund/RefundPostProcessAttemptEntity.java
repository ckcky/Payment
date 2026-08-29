package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 退款后处理尝试持久化实体（PO）：仅承载 refund_post_process_attempts 表列，映射由仓储完成。
 */
@TableName("refund_post_process_attempts")
public class RefundPostProcessAttemptEntity extends BaseEntity {

    private Long refundId;
    /** 后处理目标：FULFILLMENT / ENTITLEMENT / LEDGER。 */
    private String target;
    /** 结果：SUCCEEDED / FAILED。 */
    private String status;
    /** 结果或错误摘要。 */
    private String detail;
    private Integer attemptCount;

    public Long getRefundId() {
        return refundId;
    }

    public void setRefundId(Long refundId) {
        this.refundId = refundId;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }
}
