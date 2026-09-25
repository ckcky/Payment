package com.payment.payment.api;

import com.payment.channelgateway.api.dto.ChannelCallbackRequest;
import com.payment.payment.api.dto.RefundFactResponse;
import com.payment.payment.application.refund.RefundApplicationService;
import com.payment.payment.application.refund.RefundFactsService;
import com.payment.payment.application.refund.RefundRpcCallbackService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款领域对外 REST 接口（内部同步 RPC 面，spec 019 / D6 调整）。
 *
 * <p><b>创建入口已下线</b>：退款发起统一走 order 驱动（{@code POST /internal/orders/refund}
 * → Feign {@code POST /internal/payments/refund-command}），payment 不再直接受理退款创建
 * （ADR-0067：order 是业务编排者，payment 是能力提供方）。保留查询、resolve 人工收敛、
 * 渠道异步回调与对账事实查询端点。</p>
 *
 * <p><b>spec 038 / FR-005 / FR-007</b>：原 {@code /internal/payments/refunds/**} 与
 * {@code RefundFactsController} 已收口到本单一前缀 {@code /internal/payments/refunds/**}，
 * 与支付事实端点 {@code /internal/payments/confirmed-facts} 同源；请求 / 响应字段名与语义零变化。</p>
 */
@RestController
@RequestMapping("/internal/payments/refunds")
public class RefundController {

    private final RefundApplicationService applicationService;
    private final RefundRpcCallbackService callbackService;
    private final RefundFactsService factsService;

    public RefundController(RefundApplicationService applicationService,
                            RefundRpcCallbackService callbackService,
                            RefundFactsService factsService) {
        this.applicationService = applicationService;
        this.callbackService = callbackService;
        this.factsService = factsService;
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

    /**
     * 对账事实查询接口：向 reconciliation-service 暴露平台侧已确认的退款事实。
     *
     * <p>spec 032 / H-032-1：支持可选 {@code period} 查询参数（YYYY-MM-DD，按创建日期过滤；
     * 缺省全量，保留兼容窗口）。</p>
     */
    @GetMapping("/confirmed-facts")
    public List<RefundFactResponse> confirmedFacts(
            @RequestParam(required = false) String period) {
        return factsService.confirmedFacts(period);
    }
}
