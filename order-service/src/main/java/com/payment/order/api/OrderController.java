package com.payment.order.api;

import com.payment.order.api.dto.CreateOrderRequest;
import com.payment.order.api.dto.CreateOrderResponse;
import com.payment.order.api.dto.OrderResponse;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单领域对外 REST 接口。
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderApplicationService service;

    public OrderController(OrderApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        List<OrderLine> lines = request.items().stream()
                .map(l -> new OrderLine(l.skuId(), l.quantity()))
                .toList();
        return CreateOrderResponse.from(
                service.createOrder(request.userId(), request.merchantId(), lines));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return OrderResponse.from(service.getOrder(id));
    }
}
