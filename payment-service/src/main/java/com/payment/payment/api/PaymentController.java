package com.payment.payment.api;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.payment.api.dto.PaymentResponse;
import com.payment.payment.api.dto.ResolveRequest;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.domain.Payment;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付领域对外 REST 接口。
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentApplicationService applicationService;
    private final PaymentUnknownResolutionService resolutionService;

    public PaymentController(PaymentApplicationService applicationService,
                             PaymentUnknownResolutionService resolutionService) {
        this.applicationService = applicationService;
        this.resolutionService = resolutionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        CreatePaymentCommand command = new CreatePaymentCommand(request.transactionId(), request.orderId(),
                request.userId(), request.amountMinor(), request.currencyCode(),
                request.idempotencyKey(), request.channelCode());
        Payment payment = applicationService.createPaymentIntent(command);
        return new CreatePaymentResponse(payment.getId(), payment.getStatus().name());
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable Long id) {
        return PaymentResponse.from(applicationService.getPayment(id));
    }

    @PostMapping("/{id}/resolve")
    public PaymentResponse resolveUnknown(@PathVariable Long id, @Valid @RequestBody ResolveRequest request) {
        resolutionService.resolve(id, request.toResult());
        return PaymentResponse.from(applicationService.getPayment(id));
    }
}
