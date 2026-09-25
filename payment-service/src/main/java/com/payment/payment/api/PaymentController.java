package com.payment.payment.api;

import com.payment.channelgateway.application.ChannelGateway;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ChannelGateway channelGateway;

    /**
     * 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。
     *
     * <p>与 {@code PaymentApplicationService} / {@code PaymentPersistence} 同惯例——
     * 一旦类里出现第二个构造器，Spring 就不再能「唯一确定」用哪个，
     * 不标注 {@code @Autowired} 会直接报 {@code No default constructor found}，
     * 让<b>所有</b>加载本 Controller 的 Spring 测试连锁失败。</p>
     */
    @Autowired
    public PaymentController(PaymentApplicationService applicationService,
                             PaymentUnknownResolutionService resolutionService,
                             MockCashierProperties mockCashier,
                             ChannelGateway channelGateway) {
        this.applicationService = applicationService;
        this.resolutionService = resolutionService;
        this.mockCashier = mockCashier;
        this.channelGateway = channelGateway;
    }

    /**
     * 兼容构造（既有单测零改动）：模态探针回落为无注册表的门面（{@link ChannelGateway#none()}）。
     *
     * <p>本类只从门面取「当前是不是沙箱染色」这一个布尔，与注册表无关，
     * 故兼容构造在语义上<b>完全正确</b>，不是「假装能跑」的垫片。</p>
     */
    public PaymentController(PaymentApplicationService applicationService,
                             PaymentUnknownResolutionService resolutionService,
                             MockCashierProperties mockCashier) {
        this(applicationService, resolutionService, mockCashier, ChannelGateway.none());
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
                request.idempotencyKey(), request.channelCode(), request.merchantId());
        // spec 030 / FR-167（T44）：**染色唯一消费点**。
        // mock-cashier 语义收窄为「仅对 mock 模态生效」——沙箱**不延迟**：
        // 延迟就不调 charge，不调 charge 就拿不到凭证，沙箱闭环直接断掉。
        // spec 037 / T5b（FR-013）：模态判定经门面（网关域）回答，本类不再自己读染色上下文。
        boolean defer = mockCashier.isEnabled() && !channelGateway.isSandboxRequest();
        PaymentApplicationService.RoutedPayment routed =
                applicationService.createPaymentIntentWithRouting(command, defer);
        Payment payment = routed.payment();
        // FR-027 / FR-030（Feature 028）：对外暴露的渠道码必须是**路由后的最终渠道**，
        // 不得回显调用方请求里的原始值——请求未指定渠道时那是 null，回显既无意义、
        // 也会让收银台拿到错误渠道（演示与排障都会被误导）。
        String routedChannelCode = routed.channelCode();
        // spec 030 / FR-114（T32）：payUrl **优先取渠道返回的付款凭证**，无凭证才回落
        // 既有 mock 收银台链接。这样「真实渠道凭证 → 前端引导买家付款」的闭环才通：
        // 凭证非空时 defer 通常为 false（沙箱染色下必须真调 charge 才拿得到凭证，FR-167），
        // 但二者互不依赖——先凭证后回落的顺序保证任何组合下 payUrl 都正确。
        String payUrl = routed.credential() != null
                ? routed.credential().payload()
                : (defer ? buildPayUrl(payment, request.orderNo(), request.amountMinor(),
                request.currencyCode(), routedChannelCode) : null);
        return new CreatePaymentResponse(payment.getPaymentNo(), payment.getStatus().name(), payUrl,
                payment.getAttemptSeq(), routedChannelCode);
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
