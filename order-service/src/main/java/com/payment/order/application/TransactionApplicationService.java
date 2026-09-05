package com.payment.order.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 交易动作编排（transaction 层，Feature 016 / ADR-0054 / FR-002）。
 *
 * <p>order-service 内部分两层（负责人 2026-09-06 明确分工）：</p>
 * <ul>
 *   <li><b>transaction 层（本类）</b>：接收 payment 成功通知，基于自身
 *       {@code transaction / order} 权威状态判定「正常到账」或「重复 / 超额（surplus）」；
 *       正常 → <b>委派 order 层</b>（{@link OrderApplicationService}）执行状态推进与履约驱动；
 *       surplus → 以 {@code transactionNo + paymentNo} 直调
 *       {@link PaymentGateway#refund}（不经 order 层）发起自动退款。
 *       本层不直接执行 markPaid / confirmStock / 履约驱动。</li>
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
    private final OrderApplicationService orderLayer;
    private final PaymentGateway paymentGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public TransactionApplicationService(OrderRepository orderRepository,
                                         OrderApplicationService orderLayer,
                                         PaymentGateway paymentGateway,
                                         BusinessMetrics metrics,
                                         StructuredAuditLogger auditLogger) {
        this.orderRepository = orderRepository;
        this.orderLayer = orderLayer;
        this.paymentGateway = paymentGateway;
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

    /** surplus 处置（FR-004/FR-005）：记录多收事实并发起自动退款（幂等键含 transactionNo）。 */
    private void surplusRefund(Order order, PaymentSucceededRequest request, String cause) {
        metrics.counter("order.surplus_payment", 1.0, "module", MODULE, "cause", cause);
        auditLogger.audit("order.surplus_refund_initiated", order.getOrderNo(), request.amountMinor(),
                request.currencyCode(), "FINANCIAL_AUDIT", request.transactionNo(), "payment", request.paymentNo());
        log.warn("surplus 判定，发起自动退款 cause={} transactionNo={} paymentNo={} orderNo={} amount={}",
                cause, request.transactionNo(), request.paymentNo(), order.getOrderNo(), request.amountMinor());
        RefundCommandResponse response = paymentGateway.refund(new RefundCommandRequest(
                request.transactionNo(), request.paymentNo(), order.getOrderNo(), order.getUserId(),
                request.amountMinor(), request.currencyCode()));
        log.warn("surplus 自动退款完成 transactionNo={} paymentNo={} refundNo={} refundStatus={}",
                request.transactionNo(), request.paymentNo(), response.refundNo(), response.status());
    }
}
