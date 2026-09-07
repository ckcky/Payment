package com.payment.refund.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.payment.application.OrderGateway;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 退款结果统一后处理（spec 019 / T108，对标 {@code PaymentResultProcessor}）：
 * <b>同步受理成功 / 异步渠道回调 / resolve 人工收敛</b>三路全部收敛到此处，
 * 保证「状态机终态 → 记账冲正 → 通知 order」只有一条路径，不留双路径。
 *
 * <ol>
 *   <li><b>refunds 状态机终态</b>：终态吸收冲突/重复结果（{@code succeed()/fail()} 返回 false
 *       即为重放，幂等吸收）；</li>
 *   <li><b>payments 退款口径</b>：定稿为<b>不动 payments 状态/不加列</b>——退款事实权威台账 =
 *       {@code refunds}（累计/终态/幂等）+ {@code payment_attempts}（REFUND 尝试持渠道流水），
 *       对账经 {@code RefundFactsService} 抽取；避免 payments.refunded_minor 与 refunds 双路径漂移
 *       （ADR-0054：payment 是能力提供方，支付单保留 SUCCEEDED 事实不回滚）；</li>
 *   <li><b>ledger 冲正</b>：仅退款成功触发，幂等键 {@code REFUND:{PMRF}}（修 G5 双重前缀：
 *       前缀统一由 {@code RefundFeignLedgerPostingGateway} 添加，调用方只传 PMRF）；</li>
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
        /** 渠道异步回调推送收敛（POST /internal/refunds/{refundNo}/channel-callback）。 */
        CHANNEL_CALLBACK,
        /** 人工裁定收敛（POST /internal/refunds/{refundNo}/resolve）。 */
        RESOLVE
    }

    private final RefundRepository refundRepository;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public RefundResultProcessor(RefundRepository refundRepository,
                                 OrderGateway orderGateway,
                                 LedgerPostingGateway ledgerGateway,
                                 BusinessMetrics metrics,
                                 StructuredAuditLogger auditLogger) {
        this.refundRepository = refundRepository;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
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

        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            postLedger(refund);
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED
                || refund.getStatus() == RefundStatus.FAILED) {
            notifyOrder(refund);
        }
        return refund;
    }

    /** 退款成功 → ledger 冲正（幂等键 REFUND:{PMRF}；失败不回滚事实，由对账兜底）。 */
    private void postLedger(Refund refund) {
        try {
            // G5 双重前缀修复：调用方只传 PMRF，"REFUND:" 前缀统一由出站网关添加
            ledgerGateway.postRefundCapture(refund.getRefundNo(), refund.getRefundNo(),
                    refund.getAmountMinor(), refund.getCurrencyCode());
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
        try {
            orderGateway.notifyRefundResult(new RefundResultNotification(
                    refund.getTransactionRefundNo(), refund.getRefundNo(),
                    refund.getTransactionNo(), refund.getOrderNo(), refund.getPaymentNo(),
                    refund.getAmountMinor(), refund.getCurrencyCode(),
                    refund.getStatus().name(), refund.getFailureReason()));
        } catch (RuntimeException ex) {
            metrics.counter("refund.order_notify_failed", 1.0, "module", MODULE);
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
