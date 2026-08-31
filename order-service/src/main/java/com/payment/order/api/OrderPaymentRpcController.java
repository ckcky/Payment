package com.payment.order.api;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.order.application.OrderApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单内部 RPC（payment-service → order-service）：支付结果回写订单与交易状态。
 *
 * <p>由支付领域在支付真正成功时通过同步 RPC 触发 {@code on-payment-succeeded}（订单/交易各自按领域状态机推进，
 * 重复回调幂等吸收）；支付失败/超时时触发 {@code on-payment-failed}（释放预占库存并取消订单，013）。</p>
 */
@RestController
@RequestMapping("/internal/orders")
public class OrderPaymentRpcController {

    private final OrderApplicationService service;

    public OrderPaymentRpcController(OrderApplicationService service) {
        this.service = service;
    }

    @PostMapping("/on-payment-succeeded")
    public void onPaymentSucceeded(@RequestBody PaymentSucceededRequest request) {
        service.onPaymentSucceeded(request);
    }

    @PostMapping("/on-payment-failed")
    public void onPaymentFailed(@RequestParam Long orderId) {
        service.releaseStockForOrder(orderId);
    }
}
