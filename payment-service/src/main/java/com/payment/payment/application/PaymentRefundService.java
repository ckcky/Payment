package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 退款尝试编排（T053）：为 refund-service 提供支付金额查询与渠道退款尝试。
 *
 * <p>只执行「查询事实」与「渠道退款尝试」并回传结果，不决定退款整体状态（退款决策归属 refund-service）。
 * 渠道 UNKNOWN 原样回传，绝不臆断成败。</p>
 */
@Service
public class PaymentRefundService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentChannel channel;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PaymentRefundService(PaymentRepository paymentRepository,
                                PaymentAttemptRepository attemptRepository,
                                PaymentChannel channel,
                                BusinessMetrics metrics,
                                StructuredAuditLogger auditLogger) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.channel = channel;
        // 退款业务指标（refund.*）由拥有退款生命周期的 refund-service 记录；支付侧退款尝试
        // 仅是渠道透传（不迁移支付领域状态），故此处只注入、不记录。
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    public PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request) {
        Payment payment = paymentRepository.findByPaymentNo(request.paymentNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + request.paymentNo()));
        return new PaymentAmountQueryResponse(payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(),
                payment.getAmountMinor(), payment.getCurrencyCode(), payment.getStatus().name());
    }

    public RefundAttemptResponse refund(RefundAttemptRequest request) {
        Payment payment = paymentRepository.findByPaymentNo(request.paymentNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + request.paymentNo()));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "payment not refundable in status " + payment.getStatus());
        }
        ChannelResult result = channel.refund(new RefundRequest(request.paymentNo(), request.refundNo(),
                request.amountMinor(), request.currencyCode(), "mock"));
        // D2（spec 018）：REFUND 尝试记所属支付单金额（payment 金额），而非退款金额（request.amountMinor）
        recordRefundChannelAttempt(payment, request, result);
        String mappedStatus = switch (result.status()) {
            case SUCCESS -> "SUCCEEDED";
            case FAILURE -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
        return new RefundAttemptResponse(request.refundNo(), mappedStatus, result.channelReference());
    }

    /**
     * 退款渠道尝试落库（Feature 016 / FR-017 第②步 / N4 修复）：复用 {@code payment_attempts}
     * 落一条 REFUND 类型尝试（payment_no 关联 + channel_reference = 渠道退款流水号，唯一约束兜底），
     * 对账退款事实据此取得真实渠道退款流水号（废弃 {@code refund-{id}} 合成引用）。
     * 渠道引用重复（重试/幂等重放）按唯一约束吸收，不影响退款结果回传。
     */
    private void recordRefundChannelAttempt(Payment payment, RefundAttemptRequest request, ChannelResult result) {
        // D2：记所属支付单金额，而非退款金额
        PaymentAttempt attempt = PaymentAttempt.refundAttempt(request.paymentNo(), "mock",
                payment.getAmountMinor(), payment.getCurrencyCode());
        switch (result.status()) {
            case SUCCESS -> {
                attempt.accept(result.channelReference());
                attempt.succeed();
            }
            case FAILURE -> attempt.fail(result.reason());
            case UNKNOWN -> {
                if (result.channelReference() != null) {
                    attempt.accept(result.channelReference());
                }
                attempt.markUnknown(result.reason());
            }
        }
        try {
            attemptRepository.save(attempt);
        } catch (DuplicateKeyException ex) {
            log.warn("退款渠道尝试重复（幂等吸收）paymentNo={} refundNo={} channelRef={}",
                    request.paymentNo(), request.refundNo(), result.channelReference());
        }
    }
}
