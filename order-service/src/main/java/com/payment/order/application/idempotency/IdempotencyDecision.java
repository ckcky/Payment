package com.payment.order.application.idempotency;

import java.util.Optional;

/**
 * 下单入口幂等决策结果（ADR-0039/0040：客户端生成 Idempotency-Key，服务端仅 Redis 防重）。
 *
 * <ul>
 *   <li>{@link Type#PROCEED}：首次（或 Redis 不可用 fail-open），继续创建订单。</li>
 *   <li>{@link Type#CONFLICT}：同 key 仍在处理中（IN_PROGRESS），调用方应 409 + Retry-After 轮询。</li>
 *   <li>{@link Type#REPLAY}：同 key 已完成，携带首次响应的原始 JSON，调用方直接返回（200，不重复创建）。</li>
 *   <li>{@link Type#UNAVAILABLE}：Redis 不可用，fail-open 当作首次处理。</li>
 * </ul>
 *
 * <p>重放采用「原始 JSON 字符串」而非反序列化对象：项目未开启编译器 {@code -parameters}，
 * Jackson 反序列化 record 不可靠，故不重建对象，直接原样回放（HTTP body 形状不变）。</p>
 */
public final class IdempotencyDecision {

    public enum Type { PROCEED, CONFLICT, REPLAY, UNAVAILABLE }

    private final Type type;
    private final String storedJson;

    private IdempotencyDecision(Type type, String storedJson) {
        this.type = type;
        this.storedJson = storedJson;
    }

    public static IdempotencyDecision proceed() {
        return new IdempotencyDecision(Type.PROCEED, null);
    }

    public static IdempotencyDecision conflict() {
        return new IdempotencyDecision(Type.CONFLICT, null);
    }

    public static IdempotencyDecision replay(String storedJson) {
        return new IdempotencyDecision(Type.REPLAY, storedJson);
    }

    public static IdempotencyDecision unavailable() {
        return new IdempotencyDecision(Type.UNAVAILABLE, null);
    }

    public Type getType() {
        return type;
    }

    public boolean isProceed() {
        return type == Type.PROCEED;
    }

    public boolean isConflict() {
        return type == Type.CONFLICT;
    }

    public boolean isReplay() {
        return type == Type.REPLAY;
    }

    public boolean isUnavailable() {
        return type == Type.UNAVAILABLE;
    }

    public Optional<String> storedJson() {
        return Optional.ofNullable(storedJson);
    }
}
