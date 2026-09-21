package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 记账批次持久化实体（PO）：承载 postings 表列，平衡性规则在 {@code domain.Posting}。
 */
@TableName("postings")
public class PostingEntity extends BaseEntity {

    /** 业务单号（LP + 雪花，ADR-0062）。 */
    private String postingNo;
    /** 事件类型（spec 031 §9：LedgerTransaction 一等列，取代分录 entry_type 语义）。 */
    private String eventType;
    private String idempotencyKey;
    private String sourceType;
    private String sourceId;
    private String status;
    private String currency;
    /** 会计期间 YYYY-MM（G2，落库按 postedAt 派生）。 */
    private String period;
    /** 入账时刻（期间派生锚）。 */
    private java.time.Instant postedAt;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public java.time.Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(java.time.Instant postedAt) {
        this.postedAt = postedAt;
    }

    public String getPostingNo() {
        return postingNo;
    }

    public void setPostingNo(String postingNo) {
        this.postingNo = postingNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
