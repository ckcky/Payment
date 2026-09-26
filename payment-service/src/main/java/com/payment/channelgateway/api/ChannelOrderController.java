package com.payment.channelgateway.api;

import com.payment.channelgateway.application.ChannelOrderService;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道单只读查询端点（spec 041）。
 *
 * <h3>本控制器只做三件事</h3>
 * <ol>
 *   <li>接收请求（查询参数 / 路径变量）；</li>
 *   <li>调用<b>一个</b> {@link ChannelOrderService} 方法；</li>
 *   <li>把返回的渠道单转成响应视图。</li>
 * </ol>
 * 不得在此做业务判断、不得直连仓储、不得组合多个服务调用。
 *
 * <h3>为什么单独开这个端点</h3>
 * <p>渠道单是渠道域自己的聚合，以前只能从 payment 侧间接看到（{@code payments.current_attempt_id}
 * 反查自增主键）。排障「这笔钱到底在渠道侧是什么状态」必须能按
 * {@code channelNo} 直查——那是渠道域对外的业务身份（ADR-0063）。</p>
 *
 * <p><b>只读</b>：渠道单的状态迁移只由渠道交互与收敛路径驱动，本端点不提供任何写操作。</p>
 */
@RestController
public class ChannelOrderController {

    private final ChannelOrderService orderService;

    public ChannelOrderController(ChannelOrderService orderService) {
        this.orderService = orderService;
    }

    /** 按支付单号列出全部渠道单（PAYMENT / REFUND 一并返回，按 id 升序）。 */
    @GetMapping("/internal/channels/orders")
    public ChannelOrderListView listByPayment(@RequestParam("paymentNo") String paymentNo) {
        List<ChannelOrderView> views = orderService.findByPaymentNo(paymentNo).stream()
                .sorted(java.util.Comparator.comparing(ChannelOrder::getId))
                .map(ChannelOrderView::of)
                .toList();
        return new ChannelOrderListView(paymentNo, views);
    }

    /** 按渠道单业务号直查（{@code CH}+雪花）。 */
    @GetMapping("/internal/channels/orders/{channelNo}")
    public ChannelOrderView byChannelNo(@PathVariable("channelNo") String channelNo) {
        return orderService.findByChannelNo(channelNo)
                .map(ChannelOrderView::of)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "channel order not found: " + channelNo));
    }

    /** 渠道单响应视图：只暴露可观测字段，不含 {@code extra} 内部键的全量细节。 */
    public record ChannelOrderView(String channelNo, String paymentNo, String channelCode,
                                   String type, String status, String channelReference,
                                   String failureReason, int retryCount, String errorType,
                                   String requestedAt, String respondedAt) {

        static ChannelOrderView of(ChannelOrder order) {
            return new ChannelOrderView(
                    order.getChannelNo(), order.getPaymentNo(), order.getChannelCode(),
                    order.getAttemptType(), order.getStatus() == null ? null : order.getStatus().name(),
                    order.getChannelReference(), order.getFailureReason(), order.getRetryCount(),
                    order.getErrorType() == null ? null : order.getErrorType().name(),
                    String.valueOf(order.getRequestedAt()), String.valueOf(order.getRespondedAt()));
        }
    }

    /** {@code listByPayment} 的响应包装。 */
    public record ChannelOrderListView(String paymentNo, List<ChannelOrderView> orders) {
    }
}
