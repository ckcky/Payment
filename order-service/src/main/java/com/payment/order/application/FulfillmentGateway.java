package com.payment.order.application;

import com.payment.common.dto.rpc.FulfillmentAcceptedResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;

/**
 * 支付成功后驱动履约的出站同步 RPC 端口（order → fulfillment，Feature 016 / ADR-0054）。
 *
 * <p>职责归位：本端口原先由 payment-service 持有并直调；现改由 <b>order 层</b>在
 * transaction 层判定「正常到账」并委派后调用（markPaid + transaction.succeed() + confirmStock
 * 之后的最后一环），经由既有 {@code fulfillment → entitlement} 链授予权益（FR-003/FR-008）。</p>
 */
public interface FulfillmentGateway {

    /** 通知履约服务支付成功；下游按 orderId/paymentNo 幂等吸收重复通知。 */
    FulfillmentAcceptedResponse notifyPaymentSucceeded(PaymentSucceededRequest request);

    /**
     * 退款收口时终止履约（spec 019 / ADR-0067）：下游按 item 撤全部 PENDING 履约
     * （spec 018 明细粒度），响应 CANCELLED/SKIPPED；下游幂等吸收重复通知。
     */
    RefundFulfillmentResponse onRefund(RefundFulfillmentRequest request);
}
