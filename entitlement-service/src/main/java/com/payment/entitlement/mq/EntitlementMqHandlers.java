package com.payment.entitlement.mq;

import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.mq.EventEnvelope;
import com.payment.entitlement.application.EntitlementApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * entitlement 侧消费路由（spec 029 / 批次 D / T47-T48、FR-305）。
 *
 * <ul>
 *   <li>{@code fulfillment.completed} → 授予权益（{@code grantOnFulfillmentCompleted}，
 *       幂等由履约粒度唯一键吸收）；</li>
 *   <li>{@code fulfillment.revoked} → 回收权益（{@code revokeOnRefund}，幂等 REVOKED/NOOP）。</li>
 * </ul>
 */
@Component
public class EntitlementMqHandlers {

    private static final Logger log = LoggerFactory.getLogger(EntitlementMqHandlers.class);

    private final EntitlementApplicationService entitlementService;

    public EntitlementMqHandlers(EntitlementApplicationService entitlementService) {
        this.entitlementService = entitlementService;
    }

    /** 消费 {@code fulfillment.completed}：授予权益（幂等）。 */
    void onFulfillmentCompleted(EventEnvelope envelope) {
        Object raw = envelope.get("fulfillmentId");
        if (raw == null) {
            log.warn("fulfillment.completed 缺失 fulfillmentId，忽略 msgId={}", envelope.msgId());
            return;
        }
        FulfillmentCompletedRequest request = new FulfillmentCompletedRequest(
                Long.valueOf(String.valueOf(raw)), envelope.str("orderNo"), envelope.str("userId"));
        entitlementService.grantOnFulfillmentCompleted(request);
        log.info("MQ 消费 fulfillment.completed → entitlement 授予 [fulfillmentId={}, orderNo={}, traceId={}]",
                request.fulfillmentId(), request.orderNo(), envelope.traceId());
    }

    /** 消费 {@code fulfillment.revoked}：回收权益（幂等 REVOKED / NOOP）。 */
    void onFulfillmentRevoked(EventEnvelope envelope) {
        RefundPostProcessRequest request = new RefundPostProcessRequest(
                envelope.str("refundNo"), envelope.str("paymentNo"), envelope.str("orderNo"),
                envelope.str("userId"), envelope.str("reason"));
        entitlementService.revokeOnRefund(request);
        log.info("MQ 消费 fulfillment.revoked → entitlement 回收 [refundNo={}, orderNo={}, traceId={}]",
                request.refundNo(), request.orderNo(), envelope.traceId());
    }
}
