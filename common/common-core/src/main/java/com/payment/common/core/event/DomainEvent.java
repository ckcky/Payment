package com.payment.common.core.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 领域事件基类：事件身份、发生时间、来源模块、聚合身份与事件版本。
 *
 * <p>事件是「已发生的事实」，归属于产生它的业务模块；其他模块只能消费公开事件契约，
 * 不得访问其内部事件对象（Constitution §2.4 / plan §7）。</p>
 *
 * <p>使用标准 getter 以支持跨服务 Outbox 的 JSON 序列化。</p>
 */
public abstract class DomainEvent {

    private final String eventId;
    private final Instant occurredAt;
    private final String sourceModule;
    private final String aggregateId;
    private final long version;

    protected DomainEvent(String sourceModule, String aggregateId, long version) {
        this(UUID.randomUUID().toString(), Instant.now(), sourceModule, aggregateId, version);
    }

    protected DomainEvent(String eventId, Instant occurredAt, String sourceModule,
                          String aggregateId, long version) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.sourceModule = Objects.requireNonNull(sourceModule, "sourceModule");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.version = version;
    }

    /** 事件类型名（已发生事实名，如 {@code PaymentSucceeded}）。 */
    public abstract String getEventType();

    public String getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** 产生该事件的业务模块（如 {@code payment}）。 */
    public String getSourceModule() {
        return sourceModule;
    }

    /** 该事件所属聚合根的身份。 */
    public String getAggregateId() {
        return aggregateId;
    }

    /** 事件版本，用于乱序/去重与顺序校验。 */
    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return getEventType() + "{eventId=" + eventId + ", sourceModule=" + sourceModule
                + ", aggregateId=" + aggregateId + ", version=" + version + '}';
    }
}
