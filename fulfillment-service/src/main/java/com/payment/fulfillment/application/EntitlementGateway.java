package com.payment.fulfillment.application;

import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;

/**
 * entitlement 出站同步 RPC 端口；生产用 Feign，测试用 fake。
 *
 * <p>spec 019（ADR-0067）：履约与权益是同一条授予链的两端——退款时由 fulfillment
 * 沿「fulfillment → entitlement」既定链触发权益撤销（order 不直调 entitlement）。</p>
 */
public interface EntitlementGateway {

    EntitlementGrantedResponse notifyFulfillmentCompleted(FulfillmentCompletedRequest request);

    /** 退款撤销订单下 AVAILABLE 权益（幂等：REVOKED / NOOP）。 */
    RefundPostProcessResponse revokeOnRefund(RefundPostProcessRequest request);
}
