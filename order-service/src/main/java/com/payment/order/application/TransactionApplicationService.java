package com.payment.order.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.RefundOrder;
import com.payment.order.domain.RefundOrderStatus;
import com.payment.order.domain.Transaction;
import com.payment.order.domain.TransactionRefundRepository;
import com.payment.order.domain.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 交易动作编排（transaction 层，Feature 016 / ADR-0054；spec 019 / ADR-0067 退款升级）。
 *
 * <p>order-service 内部分两层（负责人 2026-09-06 明确分工）：</p>
 * <ul>
 *   <li><b>transaction 层（本类）</b>：接收 payment 成功通知，基于自身
 *       {@code transaction / order} 权威状态判定「正常到账」或「重复 / 超额（surplus）」；
 *       正常 → <b>委派 order 层</b>（{@link OrderApplicationService}）执行状态推进与履约驱动；
 *       surplus → 生成交易层退款单（TXRF）驱动退款（不经 order 层）。
 *       spec 019 起退款由本层驱动两层退款单：生成 TXRF → 落 transaction_refunds
 *       （幂等键 = TXRF）→ 调 payment 生成 PMRF（双号互记）→ payment 终态收敛后
 *       经 {@code on-refund-result} 回调本层收口。</li>
 *   <li><b>order 层（{@code OrderApplicationService}）</b>：订单创建 / 商品 / 金额 / 状态机；
 *       支付成功后的订单侧动作——markPaid + transaction.succeed() + confirmStock + 驱动履约。</li>
 * </ul>
 *
 * <p>surplus 判定不再依赖跨服务 409 异常（FR-007）：「多收钱」是正常业务分支，
 * order 把它当决策处理而非错误上抛，MUST NOT 对 surplus 抛 {@code ORDER_NOT_PAYABLE} 给 payment。
 * 权益由既有 {@code fulfillment → entitlement} 链授予，本层不触碰（FR-008）。</p>
 */
@Service
public class TransactionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionApplicationService.class);
    private static final String MODULE = "order";

    private final OrderRepository orderRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionRefundRepository transactionRefundRepository;
    private final OrderApplicationService orderLayer;
    private final PaymentGateway paymentGateway;
    private final FulfillmentGateway fulfillmentGateway;
    private final CatalogClient catalogClient;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public TransactionApplicationService(OrderRepository orderRepository,
                                         TransactionRepository transactionRepository,
                                         TransactionRefundRepository transactionRefundRepository,
                                         OrderApplicationService orderLayer,
                                         PaymentGateway paymentGateway,
                                         FulfillmentGateway fulfillmentGateway,
                                         CatalogClient catalogClient,
                                         BusinessMetrics metrics,
                                         StructuredAuditLogger auditLogger) {
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.transactionRefundRepository = transactionRefundRepository;
        this.orderLayer = orderLayer;
        this.paymentGateway = paymentGateway;
        this.fulfillmentGateway = fulfillmentGateway;
        this.catalogClient = catalogClient;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 支付成功通知入口（payment → order）：判定正常到账 / surplus 并分派。
     * order 不存在时上抛 {@code NOT_FOUND}（payment 侧捕获忽略，对账兜底）。
     */
    public void onPaymentSucceeded(PaymentSucceededRequest request) {
        Order order = orderRepository.findByOrderNo(request.orderNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "order not found: " + request.orderNo()));

        if (order.getStatus() == OrderStatus.PAID) {
            if (request.paymentNo().equals(order.getPaymentNo())) {
                // 同一支付单的重复通知：幂等吸收（订单侧动作已完成，不重复确认库存 / 驱动履约）
                return;
            }
            // Feature 016 / INV-2：本交易已 PAID，另一张支付单也回调成功 → surplus（多收钱）
            surplusRefund(order, request, "DUPLICATE_PAYMENT");
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            // 订单已取消 / 超时 / 关闭仍收到支付成功 → 多收钱必须原路退回（原 C5 语义，
            // 触发方由 payment 移交本层，不再向 payment 抛 409）
            surplusRefund(order, request, "ORDER_NOT_PAYABLE");
            return;
        }

        // 正常到账：委派 order 层执行 markPaid + transaction.succeed() + confirmStock + 驱动履约
        orderLayer.onPaymentSucceeded(request);
    }

    /**
     * 手工退款入口（spec 019 / T104：POST /internal/orders/refund，运维 / 演示用）。
     * 校验：订单 PAID 态族 + 可退余额（第二道校验，{@code Order.applyRefund} 收口时还有终局校验）。
     * paymentNo 缺省取订单生效支付单。
     */
    public RefundOrder createRefund(String orderNo, String paymentNo, long amountMinor, String reason) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "order not found: " + orderNo));
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.FULFILLING
                && order.getStatus() != OrderStatus.COMPLETED
                && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "order not refundable: " + orderNo + " status=" + order.getStatus());
        }
        String effectivePaymentNo = paymentNo != null ? paymentNo : order.getPaymentNo();
        if (order.getRefundableMinor() < amountMinor) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "refund exceeds refundable: refundable=" + order.getRefundableMinor()
                            + " requested=" + amountMinor);
        }
        RefundOrder refundOrder = doCreateRefund(order, effectivePaymentNo, amountMinor, reason, "MANUAL_REFUND");
        metrics.counter("order.refund_initiated", 1.0, "module", MODULE, "cause", "MANUAL_REFUND");
        return refundOrder;
    }

    /** surplus 处置（FR-004/FR-005）：记录多收事实并生成交易层退款单驱动退款（spec 019 双层单号）。 */
    private void surplusRefund(Order order, PaymentSucceededRequest request, String cause) {
        metrics.counter("order.surplus_payment", 1.0, "module", MODULE, "cause", cause);
        auditLogger.audit("order.surplus_refund_initiated", order.getOrderNo(), request.amountMinor(),
                request.currencyCode(), "FINANCIAL_AUDIT", request.transactionNo(), "payment", request.paymentNo());
        log.warn("surplus 判定，发起自动退款 cause={} transactionNo={} paymentNo={} orderNo={} amount={}",
                cause, request.transactionNo(), request.paymentNo(), order.getOrderNo(), request.amountMinor());
        // surplus 退款不走 PAID/可退校验：多收的钱必须原路退回（被退支付单不是生效支付单，
        // 从未计入 paidMinor；收口时按「paymentNo == 生效支付单」区分，不累加 refunded_minor）。
        RefundOrder refundOrder = doCreateRefund(order, request.paymentNo(), request.amountMinor(),
                cause, "SURPLUS_" + cause);
        log.warn("surplus 自动退款受理完成 transactionNo={} paymentNo={} txrf={} refundStatus={}",
                request.transactionNo(), request.paymentNo(), refundOrder.getRefundNo(), refundOrder.getStatus());
    }

    /**
     * 退款单创建内核（spec 019 / T104）：生成 TXRF → 落 transaction_refunds（REQUESTED，
     * 幂等键 = TXRF，命中直接回放）→ 调 payment 生成 PMRF → 回填双号推进 PROCESSING。
     * payment 调用失败的 REQUESTED 单：同 TXRF 重试时从本方法重放（不重复落单）。
     */
    private RefundOrder doCreateRefund(Order order, String paymentNo, long amountMinor,
                                       String reason, String cause) {
        Transaction transaction = transactionRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "transaction not found for order: " + order.getOrderNo()));

        // 同单同支付单同金额的在途/重放守卫：surplus 通知重试（同一张 PM 多次回调成功）
        // 或手工重复提交时，回放在途退款单，绝不生成第二个 TXRF 双重退款
        for (RefundOrder inFlight : transactionRefundRepository.findByOrderNo(order.getOrderNo())) {
            if (inFlight.getPaymentNo().equals(paymentNo)
                    && inFlight.getAmountMinor() == amountMinor
                    && (inFlight.getStatus() == RefundOrderStatus.REQUESTED
                        || inFlight.getStatus() == RefundOrderStatus.PROCESSING)) {
                log.info("refund replay by in-flight order txrf={} status={}",
                        inFlight.getRefundNo(), inFlight.getStatus());
                return inFlight;
            }
        }

        RefundOrder refundOrder = new RefundOrder(transaction.getTransactionNo(), order.getOrderNo(),
                paymentNo, order.getUserId(), amountMinor, order.getCurrencyCode(), reason);
        // 幂等：幂等键命中且已离开 REQUESTED → 直接回放，不重复调 payment
        RefundOrder existing = transactionRefundRepository.findByIdempotencyKey(refundOrder.getIdempotencyKey())
                .orElse(null);
        if (existing != null && existing.getStatus() != RefundOrderStatus.REQUESTED) {
            log.info("refund replay by idempotency key txrf={} status={}", existing.getRefundNo(), existing.getStatus());
            return existing;
        }
        if (existing != null) {
            refundOrder = existing; // REQUESTED 残留（上次 payment 调用失败）：同号重试
        } else {
            refundOrder = transactionRefundRepository.save(refundOrder);
        }
        auditLogger.audit("order.refund_order_created", order.getOrderNo(), amountMinor,
                order.getCurrencyCode(), "FINANCIAL_AUDIT", transaction.getTransactionNo(),
                "payment", paymentNo);

        RefundCommandResponse response = paymentGateway.refund(new RefundCommandRequest(
                refundOrder.getRefundNo(), refundOrder.getTransactionNo(), refundOrder.getPaymentNo(),
                refundOrder.getOrderNo(), refundOrder.getUserId(), refundOrder.getAmountMinor(),
                refundOrder.getCurrencyCode()));
        if ("REJECTED".equals(response.status())) {
            refundOrder.complete(RefundOrderStatus.REJECTED, response.refundNo());
        } else {
            refundOrder.accept(response.refundNo());
        }
        transactionRefundRepository.save(refundOrder);
        log.info("refund order accepted txrf={} pmrf={} status={} cause={}",
                refundOrder.getRefundNo(), refundOrder.getPaymentRefundNo(), refundOrder.getStatus(), cause);
        return refundOrder;
    }

    /**
     * 退款结果收口（spec 019 / T105，payment → order POST /internal/orders/on-refund-result）：
     * 退款单终态吸收 → 生效支付单退款时累加 transactions.refunded_minor + 订单 applyRefund
     * （PARTIALLY_REFUNDED / REFUNDED）+ 秒杀回补（普通商品 catalog 侧无配额键自然跳过）
     * + 履约终止（fulfillment 按 item 撤 PENDING，entitlement 沿 fulfillment → entitlement 链）。
     * 幂等：按 TXRF 寻址 + 终态吸收，重复通知安全。
     */
    public void onRefundResult(RefundResultNotification notification) {
        RefundOrder refundOrder = transactionRefundRepository.findByRefundNo(notification.transactionRefundNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "transaction refund not found: " + notification.transactionRefundNo()));
        RefundOrderStatus terminal = RefundOrderStatus.valueOf(notification.status());
        if (!terminal.isTerminal()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "refund result must be terminal: " + notification.status());
        }

        boolean firstTerminal = refundOrder.complete(terminal, notification.paymentRefundNo());
        transactionRefundRepository.save(refundOrder);
        if (!firstTerminal) {
            log.info("refund result replay absorbed txrf={} status={}", refundOrder.getRefundNo(), terminal);
            return;
        }
        if (terminal != RefundOrderStatus.SUCCEEDED) {
            log.warn("refund terminal (non-success) txrf={} status={} reason={}",
                    refundOrder.getRefundNo(), terminal, notification.failureReason());
            metrics.counter("order.refund_failed", 1.0, "module", MODULE, "status", terminal.name());
            return;
        }

        Order order = orderRepository.findByOrderNo(refundOrder.getOrderNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "order not found: " + refundOrder.getOrderNo()));
        boolean effectivePaymentRefund = refundOrder.refundsEffectivePayment(order);

        if (effectivePaymentRefund) {
            // 交易层账：refunded_minor 累加（幂等由 RefundOrder 首次终态迁移保证）
            Transaction transaction = transactionRepository.findByOrderNo(order.getOrderNo())
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                            "transaction not found for order: " + order.getOrderNo()));
            transaction.accumulateRefund(refundOrder.getAmountMinor());
            transactionRepository.save(transaction);

            // 订单层：超退终局校验 + 状态推进（PARTIALLY_REFUNDED / REFUNDED）
            order.applyRefund(refundOrder.getAmountMinor());
            orderRepository.save(order);
            metrics.counter("order.refund_succeeded", 1.0, "module", MODULE,
                    "orderStatus", order.getStatus().name());

            // 秒杀回补（幂等键 refund:{TXRF}:sku:{skuId}；首次终态迁移才触发，普通商品
            // catalog 侧无配额键自动跳过——SeckillStockService.rollback 的 exists 守卫）
            for (OrderItem item : order.getItems()) {
                String restockKey = "refund:" + refundOrder.getRefundNo() + ":sku:" + item.getSkuId();
                log.debug("seckill restock key={} quantity={}", restockKey, item.getQuantity());
                catalogRestock(item.getSkuId(), item.getQuantity(), restockKey);
            }

            // 履约终止（下游按 item 撤全部 PENDING；失败不阻断退款成功事实，可重入重放）
            try {
                RefundFulfillmentResponse resp = fulfillmentGateway.onRefund(new RefundFulfillmentRequest(
                        refundOrder.getRefundNo(), refundOrder.getPaymentNo(), order.getOrderNo(),
                        order.getUserId(), refundOrder.getReason()));
                log.info("fulfillment terminated on refund txrf={} fulfillmentStatus={}",
                        refundOrder.getRefundNo(), resp.status());
            } catch (RuntimeException ex) {
                log.warn("fulfillment termination failed (retry by replay) txrf={}", refundOrder.getRefundNo(), ex);
                metrics.counter("order.refund_fulfillment_terminate_failed", 1.0, "module", MODULE);
            }
            // entitlement 撤销沿 fulfillment → entitlement 既定链（order 不直调 entitlement）
        } else {
            // surplus 被退单：钱从未进订单/交易账本，只关退款单，不累加不动订单状态
            log.info("surplus refund closed (not booked) txrf={} paymentNo={}",
                    refundOrder.getRefundNo(), refundOrder.getPaymentNo());
        }
        auditLogger.audit("order.refund_result_applied", order.getOrderNo(), refundOrder.getAmountMinor(),
                refundOrder.getCurrencyCode(), "FINANCIAL_AUDIT", refundOrder.getTransactionNo(),
                "payment", refundOrder.getPaymentRefundNo());
    }

    private void catalogRestock(String skuId, int quantity, String restockKey) {
        try {
            catalogClient.rollbackSeckill(Long.parseLong(skuId), quantity);
        } catch (RuntimeException ex) {
            log.warn("seckill restock failed (retry by replay) key={}", restockKey, ex);
            metrics.counter("order.refund_restock_failed", 1.0, "module", MODULE);
        }
    }
}
