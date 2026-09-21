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
import com.payment.order.mq.OrderEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final ObjectProvider<OrderEventPublisher> mqProvider;
    /**
     * 编程式事务（spec 034 / C-19，模式同 {@code OrderApplicationService}）：
     * 仅包裹本地 DB 段，出站 Feign / MQ 一律在事务提交后执行。
     */
    private final TransactionTemplate tx;

    public TransactionApplicationService(OrderRepository orderRepository,
                                         TransactionRepository transactionRepository,
                                         TransactionRefundRepository transactionRefundRepository,
                                         OrderApplicationService orderLayer,
                                         PaymentGateway paymentGateway,
                                         FulfillmentGateway fulfillmentGateway,
                                         CatalogClient catalogClient,
                                         BusinessMetrics metrics,
                                         StructuredAuditLogger auditLogger,
                                         ObjectProvider<OrderEventPublisher> mqProvider,
                                         PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.transactionRefundRepository = transactionRefundRepository;
        this.orderLayer = orderLayer;
        this.paymentGateway = paymentGateway;
        this.fulfillmentGateway = fulfillmentGateway;
        this.catalogClient = catalogClient;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.mqProvider = mqProvider;
        this.tx = new TransactionTemplate(transactionManager);
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
     *
     * <p>受理侧串行化（spec 022 T433）：同订单并发退款在 JVM 锁内校验
     * 「可退余额 − 在途退款合计 ≥ 申请额」——原「可退余额」校验与 SUCCEEDED 收口记账
     * 存在时间窗，并发多笔在途同时通过校验会击穿总额（E2E 并发用例实证 4 笔全受理）。
     * 单机演示栈用 JVM 锁；多实例部署需演进为 DB 悲观锁 / 分布式锁。</p>
     */
    public RefundOrder createRefund(String orderNo, String paymentNo, long amountMinor, String reason) {
        synchronized (orderNo.intern()) {
            Order order = orderRepository.findByOrderNo(orderNo)
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "order not found: " + orderNo));
            if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.FULFILLING
                    && order.getStatus() != OrderStatus.COMPLETED
                    && order.getStatus() != OrderStatus.PARTIALLY_REFUNDED) {
                throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                        "order not refundable: " + orderNo + " status=" + order.getStatus());
            }
            String effectivePaymentNo = paymentNo != null ? paymentNo : order.getPaymentNo();
            long inFlight = inFlightRefundMinor(order, effectivePaymentNo);
            if (order.getRefundableMinor() - inFlight < amountMinor) {
                throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                        "refund exceeds refundable: refundable=" + order.getRefundableMinor()
                                + " inFlight=" + inFlight + " requested=" + amountMinor);
            }
            RefundOrder refundOrder = doCreateRefund(order, effectivePaymentNo, amountMinor, reason, "MANUAL_REFUND");
            metrics.counter("order.refund_initiated", 1.0, "module", MODULE, "cause", "MANUAL_REFUND");
            return refundOrder;
        }
    }

    /** 同订单同支付单的未收敛退款合计（REQUESTED/PROCESSING，占额未入账）。 */
    private long inFlightRefundMinor(Order order, String paymentNo) {
        return transactionRefundRepository.findByOrderNo(order.getOrderNo()).stream()
                .filter(r -> r.getPaymentNo().equals(paymentNo))
                .filter(r -> r.getStatus() == RefundOrderStatus.REQUESTED
                        || r.getStatus() == RefundOrderStatus.PROCESSING)
                .mapToLong(RefundOrder::getAmountMinor)
                .sum();
    }

    /**
     * 搁浅退款单重放入口（spec 034 §7.3 F-5 / T13，供 {@code StrandedRefundOrderScanner} 调用）：
     * 对「REQUESTED 且未获 payment 受理（pmrf=null）」的既有 TXRF，重放 {@code doCreateRefund}
     * 的②重试分支——同号复用 + 重发 payment 命令，绝不新建第二张 TXRF。
     *
     * <p><b>不走 {@code createRefund} 受理入口</b>：那会重新做可退余额校验，而搁浅单本身
     * 已计入在途占用，双算必然误拒；本入口不新增退款决策，只是重试一次已受理的命令——
     * 资金上限仍由 payment 侧 {@code RefundPolicy} 累计校验把守（030「最后防线」口径）。
     * 重放频次上限（≤2）与耗尽审计由扫描器负责。</p>
     */
    public RefundOrder retryStrandedRefund(RefundOrder stranded) {
        Order order = orderRepository.findByOrderNo(stranded.getOrderNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "order not found for stranded refund: " + stranded.getOrderNo()));
        metrics.counter("order.refund_stranded_replay", 1.0, "module", MODULE);
        return doCreateRefund(order, stranded.getPaymentNo(), stranded.getAmountMinor(),
                stranded.getReason(), "STRANDED_RETRY");
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

        // 同单同支付单同金额的在途守卫（spec 030 / B7，FR-230）：**必须区分「重放」与「重试」**——
        //
        //   ① 已成功推进过（渠道已受理）⇒ **回放**：直接返回在途单，**不调 payment**（避免双重退款）。
        //      判据：`paymentRefundNo != null`（已拿到 PMRF）或状态已 `PROCESSING`。
        //   ② 尚未受理（`REQUESTED` 且 `paymentRefundNo == null`）⇒ **重试**：
        //      **不在此处返回**，让它继续往下走，用同一 TXRF 重放 payment 调用
        //      （上次调用失败 / 超时，渠道侧根本没有这笔退款，重放是安全的）。
        //
        // 修复前 ①② 被一视同仁地当作「回放」直接返回 ⇒ 首调失败的退款单永久卡在 REQUESTED，
        // 既不再推进也不被回收（design-review §11 C-18）。
        //
        // FR-231：本次修复**不改「先落库后调用」的幂等前提**——退款单仍先落库再调 payment，
        // 绝不改为「先调渠道后落库」（那会让渠道已受理而本地无单，无法收敛）。
        //
        // ⚠️ 复用而非新建：`RefundOrder.idempotencyKey == refundNo`（构造时现生成雪花号），
        // 故下方「按幂等键查」对新单**永远落空**——真正的「绝不生成第二个 TXRF」守门人就是本循环。
        // 因此 ② 重试时 MUST **复用**找到的那个 inFlight 单（同号重放 payment 调用），
        // 一旦放任走到下方 `new RefundOrder(...)` 就会落出第二张 TXRF ⇒ 双重退款。
        RefundOrder unacceptedRetry = null;
        for (RefundOrder inFlight : transactionRefundRepository.findByOrderNo(order.getOrderNo())) {
            if (inFlight.getPaymentNo().equals(paymentNo)
                    && inFlight.getAmountMinor() == amountMinor) {
                boolean alreadyPushedToChannel = inFlight.getPaymentRefundNo() != null
                        || inFlight.getStatus() == RefundOrderStatus.PROCESSING;
                if (alreadyPushedToChannel) {
                    log.info("refund replay by in-flight order txrf={} status={} pmrf={}（渠道已受理，回放不重调）",
                            inFlight.getRefundNo(), inFlight.getStatus(), inFlight.getPaymentRefundNo());
                    return inFlight;
                }
                // ② 未受理且仍 REQUESTED ⇒ 复用该单重放渠道调用（不新建 TXRF）
                if (inFlight.getStatus() == RefundOrderStatus.REQUESTED) {
                    log.warn("refund retry for unaccepted txrf={} status=REQUESTED pmrf=null（首调未受理，复用同号重放渠道调用）",
                            inFlight.getRefundNo());
                    // spec 030 / B7（T18 / FR-234）：在途但未被渠道受理的退款单 MUST **可被发现**。
                    // 本 Feature 只补**指标**（告警规则与自动补偿扫描器属后续 Feature，见 tasks Q1 / H17）。
                    metrics.counter("order.refund_stranded_requested", 1.0, "module", MODULE);
                    unacceptedRetry = inFlight;
                    break;
                }
            }
        }

        RefundOrder refundOrder = unacceptedRetry != null ? unacceptedRetry
                : new RefundOrder(transaction.getTransactionNo(), order.getOrderNo(),
                paymentNo, order.getUserId(), amountMinor, order.getCurrencyCode(), reason);
        // 幂等：幂等键命中且已离开 REQUESTED → 直接回放，不重复调 payment
        RefundOrder existing = transactionRefundRepository.findByIdempotencyKey(refundOrder.getIdempotencyKey())
                .orElse(null);
        if (existing != null && existing.getStatus() != RefundOrderStatus.REQUESTED) {
            log.info("refund replay by idempotency key txrf={} status={}", existing.getRefundNo(), existing.getStatus());
            return existing;
        }
        boolean isNewRefundOrder = false;
        if (existing != null) {
            refundOrder = existing; // REQUESTED 残留（上次 payment 调用失败）：同号重试
        } else {
            refundOrder = transactionRefundRepository.save(refundOrder);
            isNewRefundOrder = true;
        }
        // spec 030 / B7：只有**真正新建** TXRF 才记「创建」审计；② 重试路径复用既有单，
        // 不重复记 created（避免审计里同一笔退款出现两条创建记录，干扰对账）。
        if (isNewRefundOrder) {
            auditLogger.audit("order.refund_order_created", order.getOrderNo(), amountMinor,
                    order.getCurrencyCode(), "FINANCIAL_AUDIT", transaction.getTransactionNo(),
                    "payment", paymentNo);
        } else {
            auditLogger.audit("order.refund_order_replayed", order.getOrderNo(), amountMinor,
                    order.getCurrencyCode(), "FINANCIAL_AUDIT", transaction.getTransactionNo(),
                    "payment", paymentNo);
        }

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
     *
     * <p><b>C-19 修复（spec 034 / ADR-0082 R-1，方案 A）</b>：本地状态迁移 MUST 同事务收口——
     * 「TXRF 终态迁移 + transactions.refunded_minor 累加 + orders.applyRefund」三者同事务，
     * 中途崩溃 / 校验违例整体回滚，payment 重推通知即可重放收敛；修复前 TXRF 先行独立提交，
     * 崩溃窗口会留下「TXRF 已终态 + order 未记账」的永久分叉（重放被终态吸收，无法自愈）。
     * 事务提交后的动作（指标 / MQ 扇出 / 回落同步 Feign / 审计）保持原序执行——
     * MQ 半消息协议自带回查补投，回落路径注释「retry by replay」语义不变。</p>
     */
    public void onRefundResult(RefundResultNotification notification) {
        RefundOrderStatus terminal = RefundOrderStatus.valueOf(notification.status());
        if (!terminal.isTerminal()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "refund result must be terminal: " + notification.status());
        }

        // ===== 本地状态迁移：单事务（C-19）。崩溃 / 校验违例在此整体回滚，绝不留下半套账 =====
        RefundOutcome outcome = tx.execute(status -> applyRefundLocally(notification, terminal));
        if (outcome == null) {
            throw BizException.of(ErrorCodes.INTERNAL_ERROR, "refund result transaction returned no outcome");
        }

        // ===== 事务已提交：事后动作（指标 / MQ / 回落同步 / 审计），任一失败不回滚已提交事实 =====
        switch (outcome.kind()) {
            case REPLAY_ABSORBED -> log.info("refund result replay absorbed txrf={} status={}",
                    outcome.refundOrder().getRefundNo(), terminal);
            case NON_SUCCESS -> {
                log.warn("refund terminal (non-success) txrf={} status={} reason={}",
                        outcome.refundOrder().getRefundNo(), terminal, notification.failureReason());
                metrics.counter("order.refund_failed", 1.0, "module", MODULE, "status", terminal.name());
            }
            case SUCCESS_BOOKED -> {
                metrics.counter("order.refund_succeeded", 1.0, "module", MODULE,
                        "orderStatus", outcome.order().getStatus().name());
                publishRefundFanout(outcome.refundOrder(), outcome.order());
            }
            case SURPLUS_CLOSED -> // surplus 被退单：钱从未进订单/交易账本，只关退款单，不累加不动订单状态
                    log.info("surplus refund closed (not booked) txrf={} paymentNo={}",
                            outcome.refundOrder().getRefundNo(), outcome.refundOrder().getPaymentNo());
        }
        if (outcome.order() != null) {
            auditLogger.audit("order.refund_result_applied", outcome.order().getOrderNo(),
                    outcome.refundOrder().getAmountMinor(), outcome.refundOrder().getCurrencyCode(),
                    "FINANCIAL_AUDIT", outcome.refundOrder().getTransactionNo(),
                    "payment", outcome.refundOrder().getPaymentRefundNo());
        }
    }

    /** 事务内动作的结果分派（kind + 事务内已加载的聚合，供提交后动作使用）。 */
    private enum RefundOutcomeKind { REPLAY_ABSORBED, NON_SUCCESS, SUCCESS_BOOKED, SURPLUS_CLOSED }

    private record RefundOutcome(RefundOutcomeKind kind, RefundOrder refundOrder, Order order) {
    }

    /** 本地状态迁移（事务内）：TXRF 终态迁移 + 生效支付单的账本累加与订单推进，全部读写在同一事务。 */
    private RefundOutcome applyRefundLocally(RefundResultNotification notification, RefundOrderStatus terminal) {
        RefundOrder refundOrder = transactionRefundRepository.findByRefundNo(notification.transactionRefundNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "transaction refund not found: " + notification.transactionRefundNo()));

        boolean firstTerminal = refundOrder.complete(terminal, notification.paymentRefundNo(),
                notification.failureReason());
        transactionRefundRepository.save(refundOrder);
        if (!firstTerminal) {
            return new RefundOutcome(RefundOutcomeKind.REPLAY_ABSORBED, refundOrder, null);
        }
        if (terminal != RefundOrderStatus.SUCCEEDED) {
            return new RefundOutcome(RefundOutcomeKind.NON_SUCCESS, refundOrder, null);
        }

        Order order = orderRepository.findByOrderNo(refundOrder.getOrderNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "order not found: " + refundOrder.getOrderNo()));
        if (!refundOrder.refundsEffectivePayment(order)) {
            return new RefundOutcome(RefundOutcomeKind.SURPLUS_CLOSED, refundOrder, order);
        }

        // 交易层账：refunded_minor 累加（幂等由 RefundOrder 首次终态迁移保证——迁移与本累加同事务）
        Transaction transaction = transactionRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "transaction not found for order: " + order.getOrderNo()));
        transaction.accumulateRefund(refundOrder.getAmountMinor());
        transactionRepository.save(transaction);

        // 订单层：超退终局校验 + 状态推进（PARTIALLY_REFUNDED / REFUNDED）
        order.applyRefund(refundOrder.getAmountMinor());
        orderRepository.save(order);
        return new RefundOutcome(RefundOutcomeKind.SUCCESS_BOOKED, refundOrder, order);
    }

    /**
     * 退款成功扇出（事务提交后，spec 029 / T34-T35）：退款成功事实经 MQ 异步扇出
     * （catalog 回补秒杀配额 + fulfillment 终止履约，后者再沿 fulfillment → entitlement 撤权益）。
     * 本地事实已提交，属 INV-3「本地事务提交后」publish 场景。
     */
    private void publishRefundFanout(RefundOrder refundOrder, Order order) {
        OrderEventPublisher mq = mqProvider.getIfAvailable();
        if (mq != null) {
            mq.publishRefundSucceeded(refundOrder, order);
        } else {
            // FR-306 回落：mq.enabled=false 时保持既有同步语义
            for (OrderItem item : order.getItems()) {
                String restockKey = "refund:" + refundOrder.getRefundNo() + ":sku:" + item.getSkuId();
                log.debug("seckill restock key={} quantity={}", restockKey, item.getQuantity());
                catalogRestock(item.getSkuId(), item.getQuantity(), restockKey);
            }
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
        }
        // entitlement 撤销沿 fulfillment → entitlement 既定链（order 不直调 entitlement）
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
