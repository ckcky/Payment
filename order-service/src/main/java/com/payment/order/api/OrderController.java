package com.payment.order.api;

import com.payment.order.api.dto.CreateOrderRequest;
import com.payment.order.api.dto.CreateOrderResponse;
import com.payment.order.api.dto.CreateOrderPaymentRequest;
import com.payment.order.api.dto.OrderResponse;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import jakarta.validation.Valid;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.idempotency.IdempotencyDecision;
import com.payment.order.application.idempotency.OrderEntryIdempotencyService;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单领域对外 REST 接口。
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderApplicationService service;
    private final OrderEntryIdempotencyService idempotency;

    public OrderController(OrderApplicationService service, OrderEntryIdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /**
     * 创建订单（下单入口幂等，ADR-0039/0040）。
     *
     * <p>{@code Idempotency-Key} 由客户端生成：同 key 的并发请求返回 409 + Retry-After 轮询；
     * 已完成同 key 重放返回 200 与首次响应（不重复创建）；未携带则不做防重。
     * Redis 不可用时 fail-open（记指标，不阻断下单）。</p>
     */
    @PostMapping
    public ResponseEntity<?> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        IdempotencyDecision decision = idempotency.check(idempotencyKey);
        if (decision.isConflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .build();
        }
        if (decision.isReplay() && decision.storedJson().isPresent()) {
            // 已完成同 key 重放：原样回放首次响应的 JSON（不重建对象、不重复创建）
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(decision.storedJson().get());
        }
        List<OrderLine> lines = request.items().stream()
                .map(l -> new OrderLine(l.skuId(), l.quantity()))
                .toList();
        CreateOrderResponse response = CreateOrderResponse.from(
                service.createOrder(request.userId(), request.merchantId(), lines, idempotencyKey));
        idempotency.complete(idempotencyKey, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 显式选渠道创建支付单（Feature 015，INV-2）：同一订单可多次调用，每次新建一张支付单。
     */
    @PostMapping("/{ref}/payments")
    public ResponseEntity<CreatePaymentResponse> createOrderPayment(
            @PathVariable String ref, @Valid @RequestBody CreateOrderPaymentRequest request) {
        CreatePaymentResponse response = service.createPaymentForOrder(ref, request.channelCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{ref}")
    public OrderResponse getOrder(@PathVariable String ref) {
        return OrderResponse.from(service.getOrder(ref));
    }
}
