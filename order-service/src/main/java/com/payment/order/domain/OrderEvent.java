package com.payment.order.domain;

import java.time.Instant;

/**
 * 订单事件轨迹条目（只读投影，spec 029 / FR-401、FR-403）。
 *
 * <p>INV-5：轨迹仅供排障与展示，业务正确性 MUST NOT 依赖它。</p>
 */
public record OrderEvent(
        String orderNo,
        String eventType,
        String topic,
        String msgId,
        String traceId,
        String producer,
        String payloadJson,
        Instant occurredAt) {
}
