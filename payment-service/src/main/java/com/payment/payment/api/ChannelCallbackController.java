package com.payment.payment.api;

import com.payment.payment.api.dto.ChannelCallbackRequest;
import com.payment.payment.api.dto.PaymentResponse;
import com.payment.payment.application.PaymentCallbackService;
import com.payment.payment.application.PaymentApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道回调入站端点（ADR-0025 / FR-001）。
 *
 * <p>本端点<b>不做鉴权判断</b>：签名校验由 {@code ChannelCallbackSignatureFilter} 前置完成，
 * 能到达这里说明签名与防重放窗口均已通过。业务侧的重复回调、乱序与终态保护由
 * {@link PaymentCallbackService} 负责（终态吸收：迟到的冲突结果不改变资金事实）。</p>
 */
@RestController
public class ChannelCallbackController {

    private final PaymentCallbackService callbackService;
    private final PaymentApplicationService applicationService;

    public ChannelCallbackController(PaymentCallbackService callbackService,
                                     PaymentApplicationService applicationService) {
        this.callbackService = callbackService;
        this.applicationService = applicationService;
    }

    @PostMapping("/internal/payments/{paymentNo}/channel-callback")
    public PaymentResponse onChannelCallback(@PathVariable String paymentNo,
                                             @Valid @RequestBody ChannelCallbackRequest request) {
        callbackService.handleCallback(paymentNo, request.toResult());
        return PaymentResponse.from(applicationService.getPaymentByNo(paymentNo));
    }
}
