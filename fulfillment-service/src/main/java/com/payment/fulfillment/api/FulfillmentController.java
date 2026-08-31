package com.payment.fulfillment.api;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 履约查询接口（只读，无命令入口——履约由 payment-service 的同步 RPC 触发）。
 */
@RestController
@RequestMapping("/fulfillments")
public class FulfillmentController {

    private final FulfillmentRepository repository;

    public FulfillmentController(FulfillmentRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public FulfillmentResponse getFulfillment(@PathVariable Long id) {
        Fulfillment fulfillment = repository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "Fulfillment not found: " + id));
        return FulfillmentResponse.from(fulfillment);
    }

    /**
     * 按订单查询履约（只读）。
     *
     * <p>支付成功触发履约后，调用方手上只有 {@code orderId}——履约 id 是 payment-service 内部
     * RPC 的返回值，不对外暴露。缺了本端点，要确认「这笔订单的履约走到哪一步」就只能直连数据库，
     * 那会绕过服务边界（宪章 IV.4 数据所有权）。故补上按订单的只读查询（spec 011 / FR-008）。</p>
     *
     * @param orderId 订单号（业务键，非主键）
     * @return 该订单的履约；不存在时抛 {@code NOT_FOUND}
     */
    @GetMapping("/by-order/{orderId}")
    public FulfillmentResponse getFulfillmentByOrderId(@PathVariable String orderId) {
        Fulfillment fulfillment = repository.findByOrderId(orderId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "Fulfillment not found for order: " + orderId));
        return FulfillmentResponse.from(fulfillment);
    }
}
