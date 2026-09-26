package com.payment.payment.api;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.payment.api.dto.PaymentResponse;
import com.payment.payment.api.dto.ResolveRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付领域对外 REST 接口。
 *
 * <h3>职责边界（spec 041 / FR-020）</h3>
 * <p>本类<b>只做三件事</b>：接收请求 → 调用<b>一个</b>应用服务方法 → 把结果转成响应 DTO。
 * 任何业务判断都 MUST NOT 出现在这里——改造前本类自己读 {@code mockCashier.isEnabled()}
 * 决定是否调渠道、自己拼收银台 URL、自己判断 {@code ref} 是数值 id 还是业务单号，
 * 这三处正是本轮要清掉的越界。</p>
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

    /**
     * 发起支付（spec 041：接口名与路径统一为 {@code pay}）。
     *
     * <p>响应里的 {@code payUrl} 由渠道网关给出（真实渠道凭证或演示收银台），
     * 本类不参与其生成，只做透传。</p>
     */
    @PostMapping("/pay")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse pay(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentApplicationService.PayResult result = applicationService.pay(request);
        Payment payment = result.payment();
        return new CreatePaymentResponse(payment.getPaymentNo(), payment.getStatus().name(),
                result.payUrl(), payment.getAttemptSeq(), result.channelCode());
    }

    /**
     * 查询支付单（spec 041 / FR-022）：按 {@code paymentNo} 与 / 或 {@code transactionId} 寻址。
     *
     * <p>取代改造前的 {@code GET /payments/{ref}}（「全数字就当主键」的双轨猜测）。</p>
     */
    @GetMapping
    public PaymentResponse query(@RequestParam(required = false) String paymentNo,
                                @RequestParam(required = false) String transactionId) {
        return PaymentResponse.from(applicationService.queryPayment(paymentNo, transactionId));
    }

    /** 人工收敛 UNKNOWN（权威裁定，需 {@code X-Admin-Token}）。返回收敛后的支付单。 */
    @PostMapping("/{paymentNo}/resolve")
    public PaymentResponse resolve(@PathVariable String paymentNo, @Valid @RequestBody ResolveRequest request) {
        return PaymentResponse.from(resolutionService.resolve(paymentNo, request.toResult()));
    }
}
