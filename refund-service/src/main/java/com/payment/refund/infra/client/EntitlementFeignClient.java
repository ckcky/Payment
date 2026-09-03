package com.payment.refund.infra.client;

import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.refund.application.EntitlementGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * refund-service → entitlement-service 的 Feign 适配器（退款后权益处理）。
 */
@FeignClient(name = "entitlement-service")
public interface EntitlementFeignClient extends EntitlementGateway {

    @PostMapping("/internal/entitlements/on-refund")
    @Override
    RefundPostProcessResponse notifyRefundPostProcess(@RequestBody RefundPostProcessRequest request);
}
