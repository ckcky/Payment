package com.payment.refund.api;

import com.payment.payment.api.dto.ChannelCallbackRequest;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundRpcCallbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款领域对外 REST 接口（内部同步 RPC 面，spec 019 / D6 调整）。
 *
 * <p><b>创建入口已下线</b>：退款发起统一走 order 驱动（{@code POST /internal/orders/refund}
 * → Feign {@code POST /internal/payments/refund-command}），payment 不再直接受理退款创建
 * （ADR-0067：order 是业务编排者，payment 是能力提供方）。保留查询、resolve 人工收敛与
 * 渠道异步回调端点。</p>
 */
@RestController
@RequestMapping("/internal/refunds")
public class RefundController {

    private final RefundApplicationService applicationService;
    private final RefundRpcCallbackService callbackService;

    public RefundController(RefundApplicationService applicationService,
                            RefundRpcCallbackService callbackService) {
        this.applicationService = applicationService;
        this.callbackService = callbackService;
    }

    // ADR-0063：查询与收敛端点一律用业务单号 refundNo（PMRF）寻址，数值主键不出服务边界。
    @GetMapping("/{refundNo}")
    public RefundResponse getRefund(@PathVariable String refundNo) {
        return RefundResponse.from(applicationService.getRefund(refundNo));
    }

    @PostMapping("/{refundNo}/resolve")
    public RefundResponse resolveRefund(@PathVariable String refundNo, @RequestBody ResolveRefundRequest request) {
        return RefundResponse.from(callbackService.resolveRefund(refundNo, request.status()));
    }

    /**
     * 渠道异步退款回调（spec 019 / D7）：渠道受理后延迟推送权威结果。
     * 验签防重放由 {@code ChannelCallbackSignatureFilter} 前置（扩展覆盖本路径）；
     * 重复回调由退款状态机终态吸收（幂等）。
     */
    @PostMapping("/{refundNo}/channel-callback")
    public RefundResponse onChannelCallback(@PathVariable String refundNo,
                                            @RequestBody ChannelCallbackRequest request) {
        return RefundResponse.from(callbackService.handleChannelCallback(refundNo, request.toResult()));
    }
}
