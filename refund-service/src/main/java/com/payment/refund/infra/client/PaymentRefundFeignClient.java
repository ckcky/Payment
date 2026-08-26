package com.payment.refund.infra.client;

import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.refund.application.PaymentRefundGateway;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * refund-service → payment-service 的 Feign 适配器。
 */
@FeignClient(name = "payment-service", url = "${services.payment.url:http://localhost:8084}")
public interface PaymentRefundFeignClient extends PaymentRefundGateway {

    @PostMapping("/internal/payments/query-amount")
    @Override
    PaymentAmountQueryResponse queryAmount(@RequestBody PaymentAmountQueryRequest request);

    @PostMapping("/internal/payments/refund-attempt")
    @Override
    RefundAttemptResponse attemptRefund(@RequestBody RefundAttemptRequest request);
}
