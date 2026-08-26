package com.payment.refund.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.idempotency.IdempotencyKey;
import com.payment.common.core.idempotency.IdempotencyRegistry;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundDecision;
import com.payment.refund.domain.RefundPolicy;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 退款申请编排（US2）：幂等受理、资格校验、渠道退款尝试、结果收敛与成功后处理。
 *
 * <p>幂等键以 {@code refund:create} 作用域登记：重复请求返回首次结果，不重复发起渠道退款；
 * 资格不通过与超退款在本地落为 REJECTED 且同样登记幂等。UNKNOWN 结果仅登记事实，
 * 交由 {@link RefundRpcCallbackService} 依据权威结果收敛，绝不臆断成败。</p>
 */
@Service
public class RefundApplicationService {

    private static final String IDEMPOTENCY_SCOPE = "refund:create";

    private final RefundRepository refundRepository;
    private final PaymentRefundGateway paymentRefundGateway;
    private final EntitlementGateway entitlementGateway;
    private final IdempotencyRegistry idempotencyRegistry;

    public RefundApplicationService(RefundRepository refundRepository,
                                    PaymentRefundGateway paymentRefundGateway,
                                    EntitlementGateway entitlementGateway,
                                    IdempotencyRegistry idempotencyRegistry) {
        this.refundRepository = refundRepository;
        this.paymentRefundGateway = paymentRefundGateway;
        this.entitlementGateway = entitlementGateway;
        this.idempotencyRegistry = idempotencyRegistry;
    }

    public Refund createRefund(CreateRefundCommand cmd) {
        IdempotencyKey key = IdempotencyKey.of(IDEMPOTENCY_SCOPE, cmd.idempotencyKey());
        Optional<String> existing = idempotencyRegistry.find(key);
        if (existing.isPresent()) {
            return requireRefund(Long.valueOf(existing.get()));
        }

        PaymentAmountQueryResponse paid = paymentRefundGateway.queryAmount(
                new PaymentAmountQueryRequest(cmd.paymentId()));

        if (!paid.status().equals("SUCCEEDED")) {
            Refund refund = newRefund(cmd);
            refund.reject("payment not in refundable state: " + paid.status());
            refundRepository.save(refund);
            idempotencyRegistry.recordIfAbsent(key, String.valueOf(refund.getId()));
            return refund;
        }

        long cumul = refundRepository.findByPaymentId(cmd.paymentId()).stream()
                .filter(r -> isCounted(r.getStatus()))
                .mapToLong(Refund::getAmountMinor)
                .sum();

        RefundDecision decision = RefundPolicy.decide(cmd.amountMinor(), cmd.currencyCode(),
                paid.paidAmountMinor(), paid.currencyCode(), cumul);

        Refund refund = newRefund(cmd);

        if (!decision.isApproved()) {
            refund.reject(decision.reason());
            refundRepository.save(refund);
            idempotencyRegistry.recordIfAbsent(key, String.valueOf(refund.getId()));
            return refund;
        }

        refundRepository.save(refund);
        if (!idempotencyRegistry.recordIfAbsent(key, String.valueOf(refund.getId()))) {
            String winnerId = idempotencyRegistry.find(key)
                    .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR, "idempotency race"));
            return requireRefund(Long.valueOf(winnerId));
        }

        refund.process();

        RefundAttemptResponse attempt = paymentRefundGateway.attemptRefund(
                new RefundAttemptRequest(refund.getId(), cmd.paymentId(), cmd.orderId(), cmd.userId(),
                        cmd.amountMinor(), cmd.currencyCode(), cmd.reason(), cmd.idempotencyKey()));

        switch (attempt.status()) {
            case "SUCCEEDED" -> refund.succeed();
            case "FAILED" -> refund.fail("channel refund failed");
            default -> refund.markUnknown("channel refund unknown");
        }

        refundRepository.save(refund);

        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            try {
                entitlementGateway.notifyRefundPostProcess(
                        new RefundPostProcessRequest(refund.getId(), cmd.paymentId(), cmd.orderId(),
                                cmd.userId(), cmd.reason()));
            } catch (RuntimeException ignored) {
                // 后处理失败不得回滚退款成功
            }
        }

        return refund;
    }

    public Refund getRefund(Long id) {
        return requireRefund(id);
    }

    private Refund newRefund(CreateRefundCommand cmd) {
        return new Refund(cmd.orderId(), cmd.paymentId(), cmd.userId(), cmd.amountMinor(),
                cmd.currencyCode(), cmd.reason(), cmd.idempotencyKey(), cmd.items());
    }

    /** 参与「累计退款」金额统计的状态：成功/部分成功/处理中/未知（均占用可退款额度）。 */
    private boolean isCounted(RefundStatus status) {
        return status == RefundStatus.SUCCEEDED
                || status == RefundStatus.PARTIALLY_SUCCEEDED
                || status == RefundStatus.PROCESSING
                || status == RefundStatus.UNKNOWN;
    }

    private Refund requireRefund(Long id) {
        return refundRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "refund not found: " + id));
    }
}
