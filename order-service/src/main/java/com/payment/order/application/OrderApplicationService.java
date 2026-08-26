package com.payment.order.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 订单应用服务（T036）：订单创建、SKU RPC 校验、价格快照，Order 1:1 Transaction，
 * 以及创建支付意图的同步 RPC（order → payment）。
 *
 * <p>只有可售 SKU 才能下单；价格在创建时冻结为快照；订单总额由明细小计累加（防溢出）。</p>
 */
@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final TransactionRepository transactionRepository;
    private final CatalogClient catalogClient;
    private final PaymentGateway paymentGateway;

    public OrderApplicationService(OrderRepository orderRepository,
                                   TransactionRepository transactionRepository,
                                   CatalogClient catalogClient,
                                   PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.catalogClient = catalogClient;
        this.paymentGateway = paymentGateway;
    }

    public CreateOrderResult createOrder(String userId, String merchantId, List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "order must have at least one line");
        }
        List<OrderItem> items = new ArrayList<>();
        String currencyCode = null;
        for (OrderLine line : lines) {
            SkuSnapshot sku = catalogClient.getSku(line.skuId());
            if (!sku.sellable()) {
                throw BizException.of(ErrorCodes.CONFLICT, "sku not sellable: " + line.skuId());
            }
            if (currencyCode == null) {
                currencyCode = sku.currencyCode();
            } else if (!currencyCode.equals(sku.currencyCode())) {
                throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                        "mixed currencies in one order are not allowed");
            }
            items.add(new OrderItem(String.valueOf(sku.skuId()), sku.skuCode(), sku.name(),
                    line.quantity(), sku.priceMinor(), sku.currencyCode()));
        }

        Order order = new Order(userId, merchantId, currencyCode, items);
        order = orderRepository.save(order);

        Transaction transaction = new Transaction(String.valueOf(order.getId()),
                order.getTotalMinor(), order.getCurrencyCode(), "PURCHASE");
        transaction = transactionRepository.save(transaction);

        order.confirm();
        orderRepository.save(order);

        CreatePaymentResponse payment = paymentGateway.createPayment(new CreatePaymentRequest(
                String.valueOf(order.getId()),
                String.valueOf(transaction.getId()),
                order.getUserId(),
                order.getTotalMinor(),
                order.getCurrencyCode(),
                "payment:" + order.getId(),
                "mock"));

        return new CreateOrderResult(order.getId(), transaction.getId(), order.getStatus(),
                order.getTotalMinor(), order.getCurrencyCode(), payment.paymentId(),
                payment.status());
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "order not found: " + id));
    }
}
