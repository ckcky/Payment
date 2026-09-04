package com.payment.refund.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
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
 * 退款申请编排（US2）：幂等受理、资格校验、渠道退款尝试、结果收敛与成功后处理。
 *
 * <p>幂等键由数据库唯一约束兜底（{@link #insertNew} 捕获重复键回放），重复请求返回首次结果，
 * 不重复发起渠道退款。同一支付下的受理以悲观锁 {@code refund_intake_locks} 串行化，
 * 防止并发读累计退款金额 + 写入之间竞态导致超退款（H1）。资格不通过与超退款在本地落为
 * REJECTED 且同样登记幂等。UNKNOWN 结果仅登记事实，交由 {@link RefundRpcCallbackService}
 * 依据权威结果收敛，绝不臆断成败。确认退款后的履约/权益/记账后处理由
 * {@link RefundPostProcessOrchestrator} 统一编排（ADR-0017 / ADR-0018）。</p>
 */
@Service
public class RefundApplicationService {

    private static final String MODULE = "refund";

    private final RefundRepository refundRepository;
    private final PaymentRefundGateway paymentRefundGateway;
    private final RefundPostProcessOrchestrator postProcessOrchestrator;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public RefundApplicationService(RefundRepository refundRepository,
                                    PaymentRefundGateway paymentRefundGateway,
                                    RefundPostProcessOrchestrator postProcessOrchestrator,
                                    BusinessMetrics metrics,
                                    StructuredAuditLogger auditLogger) {
        this.refundRepository = refundRepository;
        this.paymentRefundGateway = paymentRefundGateway;
        this.postProcessOrchestrator = postProcessOrchestrator;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    @Transactional
    public Refund createRefund(CreateRefundCommand cmd) {
        Optional<Refund> existing = refundRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            metrics.counter("refund.duplicate", 1.0, "module", MODULE);
            return existing.get();
        }

        // H1：先串行化同一支付的退款受理，再读累计退款金额，杜绝并发超退款。
        refundRepository.lockForIntake(cmd.paymentNo());

        PaymentAmountQueryResponse paid = paymentRefundGateway.queryAmount(
                new PaymentAmountQueryRequest(cmd.paymentNo()));

        if (!paid.status().equals("SUCCEEDED")) {
            Refund refund = newRefund(cmd);
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

        Refund refund = newRefund(cmd);

        if (!decision.isApproved()) {
            refund.reject(decision.reason());
            metrics.counter("refund.rejected", 1.0, "module", MODULE);
            return insertNew(refund);
        }

        refund = insertNew(refund);
        refund.process();

        RefundAttemptResponse attempt = paymentRefundGateway.attemptRefund(
                new RefundAttemptRequest(refund.getId(), cmd.paymentNo(), cmd.orderNo(), cmd.userId(),
                        cmd.amountMinor(), cmd.currencyCode(), cmd.reason(), cmd.idempotencyKey()));

        // 渠道结果只有三态：成功/失败/未知。ADR-0016 已否决（部分退款不做），
        // 渠道回传金额不再参与状态推导，SUCCEEDED 一律按全额成功收敛。
        switch (attempt.status()) {
            case "SUCCEEDED" -> refund.succeed();
            case "FAILED" -> refund.fail("channel refund failed");
            default -> refund.markUnknown("channel refund unknown");
        }

        refundRepository.save(refund);
        recordFinalTransition(refund, RefundStatus.PROCESSING);

        // 确认退款成功后统一编排后处理：履约撤销 → 权益吊销 → 记账。
        // 任一目标失败不回滚退款成功事实（Saga），由编排器落尝试记录、递增指标、写审计。
        if (refund.getStatus() == RefundStatus.SUCCEEDED
                || refund.getStatus() == RefundStatus.PARTIALLY_SUCCEEDED) {
            postProcessOrchestrator.process(refund);
        }

        return refund;
    }

    public Refund getRefund(Long id) {
        return requireRefund(id);
    }

    private Refund newRefund(CreateRefundCommand cmd) {
        Refund refund = new Refund(cmd.orderNo(), cmd.paymentNo(), cmd.userId(), cmd.amountMinor(),
                cmd.currencyCode(), cmd.reason(), cmd.idempotencyKey(), cmd.items());
        metrics.counter("refund.initiated", 1.0, "module", MODULE);
        return refund;
    }

    /** 参与「累计退款」金额统计的状态：成功/部分成功/处理中/未知（均占用可退款额度）。 */
    private boolean isCounted(RefundStatus status) {
        return status == RefundStatus.SUCCEEDED
                || status == RefundStatus.PARTIALLY_SUCCEEDED
                || status == RefundStatus.PROCESSING
                || status == RefundStatus.UNKNOWN;
    }

    /** 渠道退款尝试后的最终状态：记录业务指标与资金审计（fire-and-forget，不改变控制流）。 */
    private void recordFinalTransition(Refund refund, RefundStatus fromStatus) {
        String action = null;
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            action = "refund.succeeded";
        } else if (refund.getStatus() == RefundStatus.FAILED) {
            action = "refund.failed";
        } else if (refund.getStatus() == RefundStatus.UNKNOWN) {
            action = "refund.unknown";
        }
        if (action == null) {
            return;
        }
        metrics.counter(action, 1.0, "module", MODULE);
        auditLogger.audit(action, refund.getIdempotencyKey(), refund.getAmountMinor(),
                refund.getCurrencyCode(), fromStatus.name(), refund.getStatus().name(), "refund",
                String.valueOf(refund.getId()));
    }

    private Refund requireRefund(Long id) {
        return refundRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "refund not found: " + id));
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
