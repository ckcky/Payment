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
    private String idempotencyKey;
    private String sourceType;
    private String sourceId;
    private String status;
    private String currency;

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
