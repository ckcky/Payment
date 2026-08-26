package com.payment.common.dto.event;

import com.payment.common.core.event.DomainEvent;

/**
 * 支付结果未知事实（payment 模块产生）：渠道交互超时/无响应时进入 UNKNOWN 后广播，
 * 供 order 感知「结果待定」，绝不当成成功或失败（Constitution §3：未知状态不猜成败）。
 */
public final class PaymentUnknown extends DomainEvent {

    public static final String EVENT_TYPE = "PaymentUnknown";

    private final String orderId;
    private final String transactionId;
    private final String reason;

    public PaymentUnknown(String sourceModule, String paymentId, long version,
                          String orderId, String transactionId, String reason) {
        super(sourceModule, paymentId, version);
        this.orderId = orderId;
        this.transactionId = transactionId;
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getPaymentId() {
        return getAggregateId();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getReason() {
        return reason;
    }
}
