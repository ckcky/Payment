package com.payment.order.api;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.TransactionApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单内部 RPC（payment-service → order-service）：支付结果回写订单与交易状态。
 *
 * <p>Feature 016（ADR-0054）：支付成功入口改由 <b>transaction 层</b>
 * （{@code TransactionApplicationService}）承接——判定正常到账 / surplus，
 * 正常委派 order 层（状态推进 + confirmStock + 驱动履约），surplus 以
 * {@code transactionNo + paymentNo} 发起自动退款。order 对 surplus 不再返回 409。</p>
 */
@RestController
@RequestMapping("/internal/orders")
public class OrderPaymentRpcController {

    private final OrderApplicationService orderLayer;
    private final TransactionApplicationService transactionLayer;

    public OrderPaymentRpcController(OrderApplicationService orderLayer,
                                     TransactionApplicationService transactionLayer) {
        this.orderLayer = orderLayer;
        this.transactionLayer = transactionLayer;
    }

    @PostMapping("/on-payment-succeeded")
    public void onPaymentSucceeded(@RequestBody PaymentSucceededRequest request) {
        transactionLayer.onPaymentSucceeded(request);
    }

    @PostMapping("/on-payment-failed")
    public void onPaymentFailed(@RequestParam String orderNo) {
        orderLayer.releaseStockForOrder(orderNo);
    }
}
