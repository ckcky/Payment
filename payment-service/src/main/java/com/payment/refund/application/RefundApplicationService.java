package com.payment.refund.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundDecision;
import com.payment.refund.domain.RefundPolicy;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 退款申请编排（US2 / spec 019 T107）：幂等受理、资格校验、渠道退款尝试、结果收敛。
 *
 * <p><b>双层单号（ADR-0067）</b>：order 侧以 TXRF 发起退款命令，本服务生成支付层执行单
 * <b>PMRF</b> 落 {@code refunds}；<b>幂等键 = transaction_refund_no</b>（同 TXRF 重试回放同一
 * 执行单，可重入——微信 out_refund_no 模式语义，载体从商户单号变为上层单号）。</p>
 *
 * <p>幂等键由数据库唯一约束兜底（{@link #insertNew} 捕获重复键回放），重复请求返回首次结果，
 * 不重复发起渠道退款。同一支付下的受理以悲观锁 {@code refund_intake_locks} 串行化，
 * 防止并发读累计退款金额 + 写入之间竞态导致超退款（H1）。资格不通过与超退款在本地落为
 * REJECTED 且同样登记幂等。渠道结果三态经 {@link RefundResultProcessor} 统一收敛——
 * 同步终态当场后处理；受理在途（UNKNOWN）待渠道回调 / resolve 收敛，绝不臆断成败。</p>
 */
@Service
public class RefundApplicationService {

    private static final String MODULE = "refund";

    private final RefundRepository refundRepository;
    private final PaymentRefundGateway paymentRefundGateway;
    private final RefundResultProcessor resultProcessor;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public RefundApplicationService(RefundRepository refundRepository,
                                    PaymentRefundGateway paymentRefundGateway,
                                    RefundResultProcessor resultProcessor,
                                    BusinessMetrics metrics,
                                    StructuredAuditLogger auditLogger) {
        this.refundRepository = refundRepository;
        this.paymentRefundGateway = paymentRefundGateway;
        this.resultProcessor = resultProcessor;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    @Transactional
    public Refund createRefund(CreateRefundCommand cmd) {
        // spec 019：幂等键 = transaction_refund_no（TXRF）；存量无交易上下文路径保留原幂等键。
        String idempotencyKey = cmd.transactionRefundNo() != null
                ? cmd.transactionRefundNo()
                : cmd.idempotencyKey();

        Optional<Refund> existing = refundRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            metrics.counter("refund.duplicate", 1.0, "module", MODULE);
            return existing.get();
        }

        // H1：先串行化同一支付的退款受理，再读累计退款金额，杜绝并发超退款。
        refundRepository.lockForIntake(cmd.paymentNo());

        PaymentAmountQueryResponse paid = paymentRefundGateway.queryAmount(
                new PaymentAmountQueryRequest(cmd.paymentNo()));

        if (!paid.status().equals("SUCCEEDED")) {
            Refund refund = newRefund(cmd, idempotencyKey);
            refund.reject("payment not in refundable state: " + paid.status());
            metrics.counter("refund.rejected", 1.0, "module", MODULE);
            return insertNew(refund);
        }

        // ADR-0016 已否决（部分退款不做）：累计一律按「申请额」占位，不再区分终态/在途。
        // 在途按申请额保守占用可退款额度，防并发超退（H1）。
        long cumul = refundRepository.findByPaymentNo(cmd.paymentNo()).stream()
                .filter(r -> isCounted(r.getStatus()))
                .mapToLong(Refund::getAmountMinor)
                .sum();

        RefundDecision decision = RefundPolicy.decide(cmd.amountMinor(), cmd.currencyCode(),
                paid.paidAmountMinor(), paid.currencyCode(), cumul);

        Refund refund = newRefund(cmd, idempotencyKey);

        if (!decision.isApproved()) {
            refund.reject(decision.reason());
            metrics.counter("refund.rejected", 1.0, "module", MODULE);
            return insertNew(refund);
        }

        refund = insertNew(refund);
        refund.process();

        // ADR-0063：出站 RPC 用业务单号 refundNo（PMRF），数值主键不出服务边界。
        RefundAttemptResponse attempt = paymentRefundGateway.attemptRefund(
                new RefundAttemptRequest(refund.getRefundNo(), cmd.transactionNo(), cmd.paymentNo(),
                        cmd.orderNo(), cmd.userId(), cmd.amountMinor(), cmd.currencyCode(),
                        cmd.reason(), idempotencyKey));

        // 渠道结果三态统一经 RefundResultProcessor 收敛（T108：同步受理 / 异步回调 / resolve 一条路径）。
        // 异步受理模式渠道返回「已受理未终局」= UNKNOWN，退款停在待收敛态，等回调 / resolve 推进。
        RefundStatus before = refund.getStatus();
        ChannelResult outcome = switch (attempt.status()) {
            case "SUCCEEDED" -> ChannelResult.success(attempt.channelReference());
            case "FAILED" -> ChannelResult.businessFailure(attempt.channelReference(), "channel refund failed");
            default -> ChannelResult.businessUnknown("channel refund unknown / accepted in-flight");
        };
        refund = resultProcessor.apply(refund, outcome, RefundResultProcessor.Source.SYNC);
        auditAcceptance(refund, before);

        return refund;
    }

    /** 按业务单号查退款（ADR-0063）：对外接口不再接受数值主键。 */
    public Refund getRefund(String refundNo) {
        return requireRefund(refundNo);
    }

    private Refund newRefund(CreateRefundCommand cmd, String idempotencyKey) {
        Refund refund = new Refund(cmd.orderNo(), cmd.paymentNo(), cmd.userId(), cmd.amountMinor(),
                cmd.currencyCode(), cmd.reason(), idempotencyKey, cmd.items(),
                cmd.transactionRefundNo(), cmd.transactionNo());
        metrics.counter("refund.initiated", 1.0, "module", MODULE);
        return refund;
    }

    /** 受理审计（发起事实，与终态审计区分；fire-and-forget）。 */
    private void auditAcceptance(Refund refund, RefundStatus fromStatus) {
        auditLogger.audit("refund.attempted", refund.getIdempotencyKey(), refund.getAmountMinor(),
                refund.getCurrencyCode(), fromStatus.name(), refund.getStatus().name(), "refund",
                String.valueOf(refund.getId()));
    }

    /** 参与「累计退款」金额统计的状态：成功/部分成功/处理中/未知（均占用可退款额度）。 */
    private boolean isCounted(RefundStatus status) {
        return status == RefundStatus.SUCCEEDED
                || status == RefundStatus.PARTIALLY_SUCCEEDED
                || status == RefundStatus.PROCESSING
                || status == RefundStatus.UNKNOWN;
    }

    private Refund requireRefund(String refundNo) {
        return refundRepository.findByRefundNo(refundNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "refund not found: " + refundNo));
    }

    private Refund insertNew(Refund refund) {
        try {
            return refundRepository.save(refund);
        } catch (DuplicateKeyException e) {
            return refundRepository.findByIdempotencyKey(refund.getIdempotencyKey())
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "refund duplicate: " + refund.getIdempotencyKey()));
        }
    }
}
