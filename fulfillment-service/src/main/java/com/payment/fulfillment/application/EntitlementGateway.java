package com.payment.fulfillment.application;

import com.payment.common.dto.rpc.EntitlementGrantedResponse;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;

/** 履约完成后触发权益授予的出站同步 RPC 端口；生产用 Feign，测试用 fake。 */
public interface EntitlementGateway {

    EntitlementGrantedResponse notifyFulfillmentCompleted(FulfillmentCompletedRequest request);
}
