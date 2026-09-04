package com.payment.order.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionRepository;
import com.payment.order.domain.TransactionStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单应用服务（T036）：订单创建、SKU RPC 校验、价格快照，Order 1:1 Transaction，
 * 创建支付意图的同步 RPC（order → payment），以及下单生命周期的库存三段式控制
 * （reserve → confirm/release，库存由 catalog-service 持有，013）。
 *
 * <p>只有可售 SKU 才能下单；价格在创建时冻结为快照；订单总额由明细小计累加（防溢出）。
 * 库存策略（owner 决策）：下单预占、支付成功才扣减、失败/超时释放。</p>
 */
@Service
public class OrderApplicationService {

    private static final String MODULE = "order";

    private final OrderRepository orderRepository;
    private final TransactionRepository transactionRepository;
    private final CatalogClient catalogClient;
    private final PaymentGateway paymentGateway;
    private final BusinessMetrics metrics;
    private final OrderTimeoutScheduler timeoutScheduler;

    public OrderApplicationService(OrderRepository orderRepository,
                                   TransactionRepository transactionRepository,
                                   CatalogClient catalogClient,
                                   PaymentGateway paymentGateway,
                                   BusinessMetrics metrics,
                                   OrderTimeoutScheduler timeoutScheduler) {
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.catalogClient = catalogClient;
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;
        this.timeoutScheduler = timeoutScheduler;
    }

    public CreateOrderResult createOrder(String userId, String merchantId, List<OrderLine> lines, String reservationKey) {
        try {
            CreateOrderResult result = doCreateOrder(userId, merchantId, lines, reservationKey);
            metrics.counter("order.initiated", 1.0, "module", MODULE);
            return result;
        } catch (RuntimeException e) {
            metrics.counter("order.create_failed", 1.0, "module", MODULE);
            throw e;
        }
    }

    private CreateOrderResult doCreateOrder(String userId, String merchantId, List<OrderLine> lines, String reservationKey) {
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

        // 注：库存预占幂等键统一取 reservationId()（order:{orderId}:sku:{skuId}），
        // 因为 OrderTimeoutScheduler / onPaymentSucceeded 各自按同一公式重算该键；
        // reservationKey（客户端 Idempotency-Key）仅用于下单入口去重（012），不参与库存键构造。
        List<ReservedLine> reserved = new ArrayList<>();
        List<ReservedLine> seckillDeducted = new ArrayList<>();
        try {
            for (OrderItem item : items) {
                Long skuId = Long.parseLong(item.getSkuId());
                // 秒杀快速准入：Lua 原子预扣，不足或 Redis 不可用则拒绝（普通品未播种直接放行）。
                SeckillResult sr = catalogClient.trySeckillDeduct(skuId, item.getQuantity());
                if (!sr.allowed()) {
                    throw BizException.of(ErrorCodes.CONFLICT, "seckill stock insufficient sku=" + item.getSkuId());
                }
                // 仅真实扣减（bypassed=true 表示未播种配额、未动 Redis）才登记回滚：
                // 否则失败回滚的 INCREMENT 会凭空造出配额键，之后正常下单反被误判"秒杀库存不足"
                if (!sr.bypassed()) {
                    seckillDeducted.add(new ReservedLine(reservationId(order.getId(), item.getSkuId()), skuId, item.getQuantity()));
                }
                ReservedLine rl = new ReservedLine(reservationId(order.getId(), item.getSkuId()),
                        skuId, item.getQuantity());
                catalogClient.reserveStock(new ReserveStockCommand(rl.reservationId(), rl.skuId(), rl.quantity()));
                reserved.add(rl);
            }

            order.confirm(); // PENDING_CONFIRMATION → PENDING_PAYMENT
            orderRepository.save(order);

            // 登记订单超时（时间轮）：到点未支付则取消并释放预占库存
            timeoutScheduler.schedule(order.getId());

            // 创建支付意图（order → payment 同步 RPC）
            CreatePaymentResponse payment = paymentGateway.createPayment(new CreatePaymentRequest(
                    String.valueOf(order.getId()),
                    String.valueOf(transaction.getId()),
                    order.getUserId(),
                    order.getTotalMinor(),
                    order.getCurrencyCode(),
                    "payment:" + order.getId(),
                    "mock"));

            transaction.start();
            transactionRepository.save(transaction);

            order.recordPayment(payment.paymentId());
            orderRepository.save(order);

            return new CreateOrderResult(order.getId(), transaction.getId(), order.getStatus(),
                    order.getTotalMinor(), order.getCurrencyCode(), payment.paymentId(),
                    payment.status(), payment.payUrl());
        } catch (RuntimeException ex) {
            // 回滚：释放已预占库存并撤销订单，避免库存泄漏
            for (ReservedLine rl : reserved) {
                catalogClient.releaseStock(new ReleaseStockCommand(rl.reservationId(), rl.skuId(), rl.quantity()));
            }
            for (ReservedLine rl : seckillDeducted) {
                catalogClient.rollbackSeckill(rl.skuId(), rl.quantity());
            }
            if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION
                    || order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                order.cancel();
                orderRepository.save(order);
            }
            throw ex;
        }
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "order not found: " + id));
    }

    /**
     * 支付成功回调回写（T 新增）：payment-service 在支付真正成功时通过内部 RPC 通知，
     * 驱动 Order PENDING_PAYMENT → PAID，并确认扣减库存（reserve → sold）。重复回调幂等吸收。
     */
    @Transactional
    public void onPaymentSucceeded(PaymentSucceededRequest request) {
        Long orderId = Long.valueOf(request.orderId());
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "order not found: " + orderId));
        boolean changed = order.markPaid(request.paymentId());
        if (!changed) {
            return; // 幂等重复回调：订单已 PAID，吸收（库存也已确认过）
        }
        orderRepository.save(order);

        Transaction transaction = transactionRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "transaction not found for order: " + orderId));
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            transaction.start();
        }
        transaction.succeed();
        transactionRepository.save(transaction);

        // 支付成功：确认扣减库存（幂等键 paymentId）
        for (OrderItem item : order.getItems()) {
            catalogClient.confirmStock(new ConfirmStockCommand(
                    reservationId(order.getId(), item.getSkuId()),
                    Long.parseLong(item.getSkuId()), item.getQuantity(), request.paymentId().toString()));
        }
    }

    /**
     * 支付失败/超时释放库存（由支付失败 RPC 或订单超时调度器触发，013/014）。
     * 幂等：库存侧已释放或已确认的预占都直接吸收；订单置为 CANCELLED（如仍在待支付态）。
     */
    public void releaseStockForOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            catalogClient.releaseStock(new ReleaseStockCommand(
                    reservationId(order.getId(), item.getSkuId()),
                    Long.parseLong(item.getSkuId()), item.getQuantity()));
            catalogClient.rollbackSeckill(Long.parseLong(item.getSkuId()), item.getQuantity());
        }
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT
                || order.getStatus() == OrderStatus.PENDING_CONFIRMATION) {
            order.cancel();
            orderRepository.save(order);
        }
    }

    /** 库存预占幂等键：订单维度 + SKU。 */
    private static String reservationId(Long orderId, String skuId) {
        return "order:" + orderId + ":sku:" + skuId;
    }

    /** 已成功预占的明细（用于失败回滚）。 */
    private record ReservedLine(String reservationId, Long skuId, long quantity) {
    }
}
