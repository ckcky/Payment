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
 * 履约查询接口（只读，无命令入口——履约由 PaymentSucceeded 事件触发）。
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
}
