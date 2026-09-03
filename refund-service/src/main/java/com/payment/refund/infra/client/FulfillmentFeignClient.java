package com.payment.refund.infra.client;

import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.refund.application.FulfillmentGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * refund-service → fulfillment-service 的 Feign 适配器（退款后履约撤销，ADR-0017）。
 */
@FeignClient(name = "fulfillment-service")
public interface FulfillmentFeignClient extends FulfillmentGateway {

    @PostMapping("/internal/fulfillments/on-refund")
    @Override
    RefundFulfillmentResponse notifyRefund(@RequestBody RefundFulfillmentRequest request);
}
