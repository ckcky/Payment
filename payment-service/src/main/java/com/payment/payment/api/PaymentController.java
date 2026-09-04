package com.payment.payment.api;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.payment.api.dto.PaymentResponse;
import com.payment.payment.api.dto.ResolveRequest;
import com.payment.payment.application.CreatePaymentCommand;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.domain.Payment;
import com.payment.payment.web.MockCashierProperties;
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
    private final MockCashierProperties mockCashier;

    public PaymentController(PaymentApplicationService applicationService,
                             PaymentUnknownResolutionService resolutionService,
                             MockCashierProperties mockCashier) {
        this.applicationService = applicationService;
        this.resolutionService = resolutionService;
        this.mockCashier = mockCashier;
    }

    /**
     * 创建支付意图（ADR-0048 修订版）：mock-cashier.enabled=true 时跳过渠道内联调用
     * （Payment 停留 PROCESSING 等收银台回调），并在响应附带 {@code payUrl}；
     * 默认关闭时走既有同步 charge 主链，payUrl 为 null，既有行为与测试零变化。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        CreatePaymentCommand command = new CreatePaymentCommand(request.transactionId(), request.orderNo(),
                request.userId(), request.amountMinor(), request.currencyCode(),
                request.idempotencyKey(), request.channelCode());
        boolean defer = mockCashier.isEnabled();
        Payment payment = applicationService.createPaymentIntent(command, defer);
        String payUrl = defer ? buildPayUrl(payment, request.orderNo(), request.amountMinor(),
                request.currencyCode(), request.channelCode()) : null;
        return new CreatePaymentResponse(payment.getPaymentNo(), payment.getStatus().name(), payUrl,
                payment.getAttemptSeq(), request.channelCode());
    }

    /** 收银台页链接：mock-channel-web 的 /cashier，页面从查询串自渲染（channelCode 供收银台展示/换渠道）。 */
    private String buildPayUrl(Payment payment, String orderNo, Long amountMinor, String currencyCode,
                               String channelCode) {
        return mockCashier.getBaseUrl() + "/cashier?paymentNo=" + payment.getPaymentNo()
                + "&orderNo=" + orderNo
                + "&amountMinor=" + amountMinor
                + "&currencyCode=" + currencyCode
                + "&channelCode=" + (channelCode == null || channelCode.isBlank() ? "MOCK" : channelCode);
    }

    @GetMapping("/{ref}")
    public PaymentResponse getPayment(@PathVariable String ref) {
        return PaymentResponse.from(applicationService.getPaymentByRef(ref));
    }

    @PostMapping("/{ref}/resolve")
    public PaymentResponse resolveUnknown(@PathVariable String ref, @Valid @RequestBody ResolveRequest request) {
        resolutionService.resolve(ref, request.toResult());
        return PaymentResponse.from(applicationService.getPaymentByRef(ref));
    }
}
