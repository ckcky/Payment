package com.payment.payment.application.refund;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.payment.application.OrderGateway;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.payment.mq.PaymentEventPublisher;
import com.payment.payment.domain.Refund;
import com.payment.payment.domain.RefundRepository;
import com.payment.payment.domain.RefundStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 退款结果统一后处理（spec 019 / T108，对标 {@code PaymentResultProcessor}）：
 * <b>同步受理成功 / 异步渠道回调 / resolve 人工收敛</b>三路全部收敛到此处，
 * 保证「状态机终态 → 记账冲正 → 通知 order」只有一条路径，不留双路径。
 *
 * <ol>
 *   <li><b>refunds 状态机终态</b>：终态吸收冲突/重复结果（{@code succeed()/fail()} 返回 false
 *       即为重放，幂等吸收）；</li>
 *   <li><b>channel_orders REFUND 尝试行收敛</b>：权威终态（SUCCESS/FAILURE）同步到 payment 域
 *       对应尝试行（按渠道流水号精确匹配，resolve 无引用回退最近未收敛行）——异步受理落 UNKNOWN
 *       的尝试行不再永久滞留（fix）；失败仅 WARN，不影响退款事实；</li>
 *   <li><b>payments 退款口径</b>：定稿为<b>不动 payments 状态/不加列</b>——退款事实权威台账 =
 *       {@code refunds}（累计/终态/幂等）+ {@code channel_orders}（REFUND 尝试持渠道流水），
 *       对账经 {@code RefundFactsService} 抽取；避免 payments.refunded_minor 与 refunds 双路径漂移
 *       （ADR-0054：payment 是能力提供方，支付单保留 SUCCEEDED 事实不回滚）；</li>
 *   <li><b>ledger 冲正</b>：仅退款成功触发，发 {@code REFUND} 事件（spec 031 §9 / ADR-0077），
 *       merchantId/channelCode 反查所属 payment；幂等键由账本按 {@code REFUND:{refundNo}} 派生；</li>
 *   <li><b>通知 order</b>：{@code POST /internal/orders/on-refund-result}（TXRF+PMRF 双号，
 *       ADR-0067）——业务下游扇出（履约终止/权益撤销/秒杀回补）移交 order 侧收口，
 *       原 refund 包对 fulfillment/entitlement 的直调扇出已删除（最小迁移 + 不留双路径）。</li>
 * </ol>
 *
 * <p>记账/通知失败不回滚退款成功事实（Saga，禁 2PC）：WARN + 指标留痕，对账/人工重放兜底。</p>
 */
@Component
public class RefundResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundResultProcessor.class);
    private static final String MODULE = "refund";

    /** 收敛来源（观测标签）：同步受理 / 异步渠道回调 / resolve 人工收敛。 */
    public enum Source {
        /** 渠道同步应答直接收敛（refund() 调用返回终态）。 */
        SYNC,
        /** 渠道异步回调推送收敛（POST /internal/payments/refunds/{refundNo}/channel-callback）。 */
        CHANNEL_CALLBACK,
        /** 人工裁定收敛（POST /internal/payments/refunds/{refundNo}/resolve）。 */
        RESOLVE,
        /** 渠道主动查询收敛（spec 034 §7.3 / T12：RefundUnknownQueryScheduler 权威答复，与 resolve 同构）。 */
        CHANNEL_QUERY
    }

    private final RefundRepository refundRepository;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    private final RefundAttemptSettlementGateway attemptSettlementGateway;
    /** spec 031 §9：REFUND 事件反查所属 payment 的 merchantId / 生效渠道码（数据路径已在退款域）。 */
    private final PaymentRefundGateway paymentRefundGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;
    /** spec 029 / FR-203 / T30：`mq.enabled=true` 时存在，走事务消息；否则回落同步 Feign（FR-306）。 */
    private final PaymentEventPublisher mq;

    /**
     * 出站失败台账登记器（spec 034 §9.2 M7）：order notify 失败 →
     * {@code ORDER_NOTIFY_REFUND_RESULT:{refundNo}} PENDING 行（原请求重发 = 重放）。
     * 可选注入（required=false）：手工构造（既有测试）缺省 null = 不登记，行为与 034 前一致（SC-012）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.payment.posting.application.PostingPendingRecorder pendingRecorder;

    public RefundResultProcessor(RefundRepository refundRepository,
                                 OrderGateway orderGateway,
                                 LedgerPostingGateway ledgerGateway,
                                 RefundAttemptSettlementGateway attemptSettlementGateway,
                                 // @Lazy：refundResultProcessor → PaymentRefundGateway → paymentRefundService
                                 // → 渠道注册表 → 回调适配器的装配环在此打断（运行期首用才解析）
                                 @org.springframework.context.annotation.Lazy
                                 PaymentRefundGateway paymentRefundGateway,
                                 BusinessMetrics metrics,
                                 StructuredAuditLogger auditLogger,
                                 ObjectProvider<PaymentEventPublisher> mqProvider) {
        this.refundRepository = refundRepository;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.attemptSettlementGateway = attemptSettlementGateway;
        this.paymentRefundGateway = paymentRefundGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.mq = mqProvider == null ? null : mqProvider.getIfAvailable();
    }

    /**
     * 应用一次渠道结果并统一后处理。
     *
     * @return 收敛后的退款聚合（终态吸收后可能与入参同例）
     */
    public Refund apply(Refund refund, ChannelResult outcome, Source source) {
        RefundStatus fromStatus = refund.getStatus();
        boolean changed = switch (outcome.status()) {
            case SUCCESS -> refund.succeed();
            case FAILURE -> refund.fail(outcome.reason() == null ? "channel refund failed" : outcome.reason());
            case UNKNOWN -> refund.markUnknown(outcome.reason() == null ? "refund still unknown" : outcome.reason());
        };
        if (!changed) {
            // 终态吸收 / 重复幂等：不重复记账、不重复通知
            metrics.counter("refund.duplicate_result", 1.0, "module", MODULE, "source", source.name());
            log.info("refund result replay absorbed refundNo={} status={} source={}",
                    refund.getRefundNo(), refund.getStatus(), source);
            return refund;
        }
        refundRepository.save(refund);
        recordFinalTransition(refund, fromStatus, source);

        // 退款尝试同步收敛（fix：UNKNOWN 落库的 REFUND 尝试行随权威结果收敛终态；
        // SYNC 终态路径在此被终态吸收，异步/resolve 路径在此完成 UNKNOWN → 终态的补齐）。
        if (outcome.status() != ChannelResult.Status.UNKNOWN) {
            try {
                attemptSettlementGateway.convergeToTerminal(
                        refund.getPaymentNo(), outcome.channelReference(), outcome);
            } catch (RuntimeException ex) {
                // 尝试行收敛失败不影响退款事实与下游（观测数据，非资金路径）
                log.warn("退款尝试收敛失败（不影响退款事实）refundNo={} paymentNo={} reason={}",
                        refund.getRefundNo(), refund.getPaymentNo(), ex.getMessage());
            }
        }

        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            postLedger(refund);
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED
                || refund.getStatus() == RefundStatus.FAILED) {
            notifyOrder(refund);
        }
        return refund;
    }

    /** 退款成功 → REFUND 事件（幂等键由账本按 REFUND:{refundNo} 派生；失败不回滚事实，对账兜底）。 */
    private void postLedger(Refund refund) {
        try {
            // spec 031 §9：merchantId/channelCode 反查所属 payment（不新增 refunds 列——payment 是事实源）
            PaymentAmountQueryResponse paid = paymentRefundGateway.queryAmount(
                    new PaymentAmountQueryRequest(refund.getPaymentNo()));
            ledgerGateway.postRefundCapture(new LedgerPostingGateway.RefundCaptureFacts(
                    refund.getRefundNo(), paid.merchantId(), paid.channelCode(),
                    refund.getAmountMinor(), refund.getCurrencyCode()));
        } catch (RuntimeException ex) {
            metrics.counter("refund.ledger_posting_failed", 1.0, "module", MODULE);
            log.error("退款记账失败（事实不回滚，对账兜底）refundNo={} reason={}",
                    refund.getRefundNo(), ex.getMessage());
        }
    }

    /** 退款终态（成功/失败）→ 通知 order 收口（TXRF+PMRF 双号；失败不回滚事实，可重入重放）。 */
    private void notifyOrder(Refund refund) {
        if (refund.getTransactionRefundNo() == null) {
            // 存量手工退款无上层单，无需通知 order
            log.debug("refund has no transactionRefundNo, skip order notification refundNo={}", refund.getRefundNo());
            return;
        }
        RefundResultNotification notification = new RefundResultNotification(
                refund.getTransactionRefundNo(), refund.getRefundNo(),
                refund.getTransactionNo(), refund.getOrderNo(), refund.getPaymentNo(),
                refund.getAmountMinor(), refund.getCurrencyCode(),
                refund.getStatus().name(), refund.getFailureReason());
        // spec 029 / T30、FR-203：refund.result 改事务消息（点对点 → order），
        // 替代同步 OrderGateway.notifyRefundResult。本地事务（refunds 落库）已在上方完成，
        // prepare→commit 即可（INV-3）。SC-1：同步通知点清零。
        try {
            if (mq != null) {
                mq.publishRefundResult(notification);
            } else {
                orderGateway.notifyRefundResult(notification);
            }
        } catch (RuntimeException ex) {
            metrics.counter("refund.order_notify_failed", 1.0, "module", MODULE);
            // 出站失败台账（spec 034 §9.2 M7）：登记原通知载荷，交由补投器退避重发；
            // 登记自身失败不抛（R-1：退款成功事实永不因台账写入失败回滚）
            if (pendingRecorder != null) {
                pendingRecorder.recordFailure(
                        com.payment.posting.application.PostingEventTypes.ORDER_NOTIFY_REFUND_RESULT,
                        "REFUND", refund.getRefundNo(),
                        com.payment.posting.application.PostingEventTypes.ORDER_NOTIFY_REFUND_RESULT
                                + ":" + refund.getRefundNo(),
                        notification, ex.getMessage());
            }
            log.warn("退款结果通知 order 失败（事实不回滚，重试/对账兜底）refundNo={} txrf={} reason={}",
                    refund.getRefundNo(), refund.getTransactionRefundNo(), ex.getMessage());
        }
    }

    /** 终态迁移的业务指标与资金审计（fire-and-forget，不改变控制流）。 */
    private void recordFinalTransition(Refund refund, RefundStatus fromStatus, Source source) {
        String action = switch (refund.getStatus()) {
            case SUCCEEDED, PARTIALLY_SUCCEEDED -> "refund.succeeded";
            case FAILED -> "refund.failed";
            case UNKNOWN -> "refund.unknown";
            default -> null;
        };
        if (action == null) {
            return;
        }
        metrics.counter(action, 1.0, "module", MODULE, "source", source.name());
        auditLogger.audit(action, refund.getIdempotencyKey(), refund.getAmountMinor(),
                refund.getCurrencyCode(), fromStatus.name(), refund.getStatus().name(), "refund",
                String.valueOf(refund.getId()));
    }
}
