package com.payment.fulfillment.domain;

/**
 * 履约状态机（data-model §Fulfillment）：
 * 待履约 → 处理中 → 已交付 / 部分交付 / 失败；待履约可取消。
 *
 * <p>状态只能通过 {@link Fulfillment} 的领域方法推进，非法迁移抛
 * {@code STATE_TRANSITION_VIOLATION}；支付成功只「触发」履约，不决定最终履约状态。</p>
 */
public enum FulfillmentStatus {

    PENDING,
    PROCESSING,
    DELIVERED,
    PARTIALLY_DELIVERED,
    FAILED,
    CANCELLED
}
