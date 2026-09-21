package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.channel.ChannelAttemptRecorder;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.mq.PaymentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 共享的「加载支付与当前尝试 → 应用渠道结果 → 持久化 → 支付指令编排」。
 * 回调（{@link PaymentCallbackService}）与未知收敛（{@link PaymentUnknownResolutionService}）复用，
 * 保证通知与记账只在支付真正迁移为成功时执行一次。
 *
 * <p>Feature 016（ADR-0054）职责归位：payment 退回<b>能力提供方</b>——支付成功后编排完成
 * 自身支付指令（渠道结果落 {@code payment_attempts} + 记账 {@code ledgerGateway.postPaymentCapture}），
 * 业务侧扇出<b>仅通知 order-service</b>（order 为业务编排者，由其 transaction 层判定正常/surplus
 * 并驱动履约 / 自动退款）。payment MUST NOT 直调 FulfillmentGateway 或 AutoRefundGateway。</p>
 */
@Component
public class PaymentResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultProcessor.class);

    private final PaymentRepository paymentRepository;
    private final ChannelAttemptRecorder attemptRecorder;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;
    private final LimitSettlementHook limitSettlement;
    /** spec 029 / FR-201 / T29：`mq.enabled=true` 时存在，走事务消息；否则回落同步 Feign（FR-306）。 */
    private final PaymentEventPublisher mq;

    /**
     * 出站失败台账登记器（spec 034 §9.2 M7）：order notify 失败 → {@code ORDER_NOTIFY_SUCCEEDED}
     * PENDING 行（原请求重发 = 重放）。可选注入（required=false）：手工构造（既有测试）缺省
     * null = 不登记，行为与 034 前完全一致（SC-012）。
     */
    @Autowired(required = false)
    private com.payment.posting.application.PostingPendingRecorder pendingRecorder;

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @Autowired
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway,
                                  BusinessMetrics metrics,
                                  LimitSettlementHook limitSettlement,
                                  ObjectProvider<PaymentEventPublisher> mqProvider) {
        this(paymentRepository, attemptRecorder, orderGateway, ledgerGateway, metrics,
                new StructuredAuditLogger(), limitSettlement,
                mqProvider == null ? null : mqProvider.getIfAvailable());
    }

    /** 显式指定审计器（测试场景可捕获 FINANCIAL_AUDIT；生产走默认构造）。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger) {
        this(paymentRepository, attemptRecorder, orderGateway, ledgerGateway, metrics, auditLogger,
                LimitSettlementHook.noop(), null);
    }

    /** 全参构造：显式给出额度结算钩子（spec 027）。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger,
                                  LimitSettlementHook limitSettlement) {
        this(paymentRepository, attemptRecorder, orderGateway, ledgerGateway, metrics, auditLogger,
                limitSettlement, null);
    }

    /** 全参构造（含 MQ 发布器，spec 029）。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger,
                                  LimitSettlementHook limitSettlement,
                                  PaymentEventPublisher mq) {
        this.paymentRepository = paymentRepository;
        this.attemptRecorder = attemptRecorder;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.limitSettlement = limitSettlement;
        this.mq = mq;
    }

    /**
     * 兼容构造（Feature 028 / FR-036）：仅接「按 attempt 写」的渠道层端口兜底实现，
     * 保持既有「无账本 / 无指标」重载签名语义——既有测试零改动（SC-012）。
     *
     * <p>{@code InMemoryPaymentAttemptRepository} 已直接实现 {@link ChannelAttemptRecorder}，
     * 故既有测试传仓储即可匹配本构造，无需额外的仓储重载（避免重载歧义）。</p>
     */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway) {
        this(paymentRepository, attemptRecorder, orderGateway,
                facts -> {
                },
                new NoopBusinessMetrics());
    }

    /** 兼容构造：显式指定记账网关（测试场景），指标用空实现。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway) {
        this(paymentRepository, attemptRecorder, orderGateway, ledgerGateway, new NoopBusinessMetrics());
    }

    /** 兼容构造：显式指定记账网关与指标（测试场景），审计器取默认实现。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  ChannelAttemptRecorder attemptRecorder,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway,
                                  BusinessMetrics metrics) {
        this(paymentRepository, attemptRecorder, orderGateway, ledgerGateway, metrics,
                new StructuredAuditLogger(), LimitSettlementHook.noop());
    }

    /** 返回支付是否真正发生了状态迁移（据此决定是否已触发订单通知与记账）。 */
    public boolean applyAndNotify(String paymentNo, ChannelResult result) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentNo));
        PaymentAttempt attempt = attemptRecorder.require(payment.getCurrentAttemptId());
        // Feature 028 / FR-004：先收敛 attempt（渠道层）→ 再推进 payment（payment 层），
        // 顺序与拆分前一致（plan §B4 风险点）。
        attemptRecorder.converge(attempt, result);
        boolean changed = PaymentResultApplier.applyPayment(payment, result);
        paymentRepository.save(payment);
        attemptRecorder.save(attempt);
        if (changed && result.status() == ChannelResult.Status.SUCCESS) {
            PaymentSucceededRequest request = PaymentResultApplier.toSucceededRequest(payment);
            // spec 029 / T28-T30、FR-201：payment.succeeded 改事务消息（点对点 → order），
            // 替代同步 OrderGateway.notifyPaymentSucceeded。SC-1：同步通知点清零。
            // 本地事务已在上方 save 完成，此处 prepare→commit 即可（INV-3「先事务后可见」）。
            if (mq != null) {
                try {
                    mq.publishPaymentSucceeded(request);
                } catch (RuntimeException ex) {
                    // commit 失败不回滚支付成功事实（INV-1）；半消息由回查按 payments 表补投
                    log.warn("MQ 发布 payment.succeeded 失败（事实不回滚，回查补投）paymentNo={} reason={}",
                            payment.getPaymentNo(), ex.getMessage());
                    metrics.counter("payment.order_notify_failed", 1.0, "module", "payment");
                    recordNotifyFailure(payment.getPaymentNo(), request, ex);
                }
            } else {
                try {
                    orderGateway.notifyPaymentSucceeded(request);
                } catch (RuntimeException ex) {
                    // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）；
                    // Feature 016：order 不再抛 409——surplus 判定与自动退款发起归 order transaction 层。
                    // T109：不再静默吞异常——WARN + 指标留痕，供监控告警与对账兜底。
                    log.warn("支付成功通知 order 失败（事实不回滚，对账兜底）paymentNo={} orderNo={} reason={}",
                            payment.getPaymentNo(), payment.getOrderNo(), ex.getMessage());
                    metrics.counter("payment.order_notify_failed", 1.0, "module", "payment");
                    recordNotifyFailure(payment.getPaymentNo(), request, ex);
                    // spec 002 / T024：「订单非法前态拒绝」是资金风险信号——钱已收、订单侧不认，
                    // 仅靠通用指标会被淹没在 RPC 抖动里，故单独审计留痕 + 专用指标，供人工介入与对账兜底。
                    if (isIllegalOrderState(ex)) {
                        auditLogger.audit("payment.order_illegal_state_rejected", payment.getPaymentNo(),
                                payment.getAmountMinor(), payment.getCurrencyCode(),
                                payment.getStatus().name(), "ORDER_REJECTED",
                                "payment", payment.getPaymentNo());
                        metrics.counter("payment.order_illegal_state_rejected", 1.0, "module", "payment");
                        log.warn("订单非法前态拒绝：支付已成功但订单侧拒绝接收 paymentNo={} orderNo={} code={}",
                                payment.getPaymentNo(), payment.getOrderNo(), ((BizException) ex).getCode());
                    }
                }
            }
            // 已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）；记账属 payment 层支付指令编排，
            // 保留在 payment 内（ADR-0054）。记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009）。
            // Feature 015 / C2：幂等键用 paymentNo 维度（PAYMENT:{paymentNo}），
            // 一交易多支付单时每张支付单独立记账，不再复用支付幂等键避免撞键静默少记账。
            //
            // spec 031（FR-101 / ADR-0077）：改传**已确认财务事实**（Financial Fact）——
            // paymentNo + merchantId + 渠道码 + 金额；科目/借贷由账本按 Posting Rule 推导。
            ledgerGateway.postPaymentCapture(new LedgerPostingGateway.PaymentCaptureFacts(
                    payment.getPaymentNo(), payment.getMerchantId(), attempt.getChannelCode(),
                    payment.getAmountMinor(), 0L, 0L, payment.getCurrencyCode()));
        }
        // 额度结算（spec 027 / FR-013，ADR-0071 D4/D12）：与记账**同级**挂在 changed=true 分支，
        // 保证「支付真正发生状态迁移」才结算一次。UNKNOWN 不结算（INV-5：保守占用，不猜成败）。
        //
        // 结算失败不回滚支付事实（ADR-0009 哲学）：钩子内部走 REQUIRES_NEW 独立短事务并自行吞掉
        // 异常，残留不一致由 LimitCompensationScanner 以 payment 为事实源收敛（FR-016）。
        if (changed && result.status() != ChannelResult.Status.UNKNOWN) {
            limitSettlement.settle(payment);
        }
        return changed;
    }

    /** 订单侧「非法前态拒绝」的语义码：订单不在可支付状态（如已支付 / 已关闭）。 */
    private static boolean isIllegalOrderState(Throwable ex) {
        if (!(ex instanceof BizException biz)) {
            return false;
        }
        String code = biz.getCode();
        return ErrorCodes.STATE_TRANSITION_VIOLATION.equals(code)
                || ErrorCodes.ORDER_NOT_PAYABLE.equals(code);
    }

    /**
     * order notify 失败 → 出站失败台账登记（spec 034 §9.2 M7）：原请求载荷落
     * {@code ORDER_NOTIFY_SUCCEEDED:{paymentNo}} PENDING 行，由补投器退避重发；
     * 登记自身失败不抛（R-1：支付成功事实永不因台账写入失败回滚）。
     */
    private void recordNotifyFailure(String paymentNo, PaymentSucceededRequest request, RuntimeException ex) {
        if (pendingRecorder == null) {
            return; // 手工构造（既有测试）缺省不登记
        }
        pendingRecorder.recordFailure(
                com.payment.posting.application.PostingEventTypes.ORDER_NOTIFY_SUCCEEDED,
                "PAYMENT", paymentNo,
                com.payment.posting.application.PostingEventTypes.ORDER_NOTIFY_SUCCEEDED + ":" + paymentNo,
                request, ex.getMessage());
    }
}
