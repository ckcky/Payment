package com.payment.posting.infra.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.posting.domain.PendingPosting;

/**
 * 出站失败台账持久化实体（PO）：仅承载 pending_postings 表列（spec 031 §12 / 034 §9）。
 *
 * <p><b>不继承 BaseEntity</b>（plan §2.1）：台账是基础设施行为记录，非业务聚合——
 * 无乐观锁（单实例调度 + 终态幂等吸收保证并发正确）、无 created_by/updated_by；
 * {@code created_at / updated_at} 由 DB 维护（DEFAULT / ON UPDATE CURRENT_TIMESTAMP），
 * 实体不映射这两列：insert 时不写（取 DB 当前时刻），update 时 MyBatis-Plus 不触碰，
 * {@code updated_at} 随真实列变更自动刷新——这正是「退避到期 = updated_at + backoff」的时钟源。</p>
 */
@TableName("pending_postings")
public class PendingPostingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventType;
    private String sourceType;
    private String sourceId;
    private String idempotencyKey;
    private String payloadJson;
    /** NULL 可空：REPOSTED 时清空失败原因，需要显式置 NULL（ALWAYS 策略：null 也进 UPDATE SET）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failReason;
    private Integer retryCount;
    private String status;

    public static PendingPostingEntity fromDomain(PendingPosting posting) {
        PendingPostingEntity entity = new PendingPostingEntity();
        entity.id = posting.getId();
        entity.eventType = posting.getEventType();
        entity.sourceType = posting.getSourceType();
        entity.sourceId = posting.getSourceId();
        entity.idempotencyKey = posting.getIdempotencyKey();
        entity.payloadJson = posting.getPayloadJson();
        entity.failReason = posting.getFailReason();
        entity.retryCount = posting.getRetryCount();
        entity.status = posting.getStatus().name();
        return entity;
    }

    public PendingPosting toDomain() {
        return PendingPosting.rehydrate(id, eventType, sourceType, sourceId,
                idempotencyKey, payloadJson, failReason,
                retryCount == null ? 0 : retryCount,
                PendingPosting.PostingStatus.valueOf(status));
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getStatus() {
        return status;
    }
}
