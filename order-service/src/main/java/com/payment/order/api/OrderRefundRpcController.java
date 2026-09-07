package com.payment.order.api;

import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.order.application.TransactionApplicationService;
import com.payment.order.domain.RefundOrder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单退款 RPC 面（spec 019 / ADR-0067）：
 * <ul>
 *   <li>{@code POST /internal/orders/refund} —— 手工/运维/演示发起退款（order 驱动两层退款单）。</li>
 *   <li>{@code POST /internal/orders/on-refund-result} —— payment 退款终态收敛后回调用（TXRF+PMRF 双号）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/orders")
public class OrderRefundRpcController {

    /** 手工退款请求体（paymentNo 缺省取订单生效支付单）。 */
    public record ManualRefundRequest(String orderNo, String paymentNo, long amountMinor, String reason) {
    }

    /** 退款受理响应：双号互记（txrf=交易层退款单，pmrf=支付层退款执行单）。 */
    public record RefundOrderResponse(String txrf, String pmrf, String status) {
    }

    private final TransactionApplicationService transactionLayer;

    public OrderRefundRpcController(TransactionApplicationService transactionLayer) {
        this.transactionLayer = transactionLayer;
    }

    @PostMapping("/refund")
    public RefundOrderResponse refund(@RequestBody ManualRefundRequest request) {
        RefundOrder refundOrder = transactionLayer.createRefund(
                request.orderNo(), request.paymentNo(), request.amountMinor(), request.reason());
        return new RefundOrderResponse(refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(),
                refundOrder.getStatus().name());
    }

    @PostMapping("/on-refund-result")
    public void onRefundResult(@RequestBody RefundResultNotification notification) {
        transactionLayer.onRefundResult(notification);
    }
}
