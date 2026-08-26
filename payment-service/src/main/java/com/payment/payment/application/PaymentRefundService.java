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
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

/**
 * 退款尝试编排（T053）：为 refund-service 提供支付金额查询与渠道退款尝试。
 *
 * <p>只执行「查询事实」与「渠道退款尝试」并回传结果，不决定退款整体状态（退款决策归属 refund-service）。
 * 渠道 UNKNOWN 原样回传，绝不臆断成败。</p>
 */
@Service
public class PaymentRefundService {

    private final PaymentRepository paymentRepository;
    private final PaymentChannel channel;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PaymentRefundService(PaymentRepository paymentRepository,
                                PaymentChannel channel,
                                BusinessMetrics metrics,
                                StructuredAuditLogger auditLogger) {
        this.paymentRepository = paymentRepository;
        this.channel = channel;
        // 退款业务指标（refund.*）由拥有退款生命周期的 refund-service 记录；支付侧退款尝试
        // 仅是渠道透传（不迁移支付领域状态），故此处只注入、不记录。
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    public PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request) {
        Payment payment = paymentRepository.findById(request.paymentId())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + request.paymentId()));
        return new PaymentAmountQueryResponse(payment.getId(), payment.getOrderId(), payment.getUserId(),
                payment.getAmountMinor(), payment.getCurrencyCode(), payment.getStatus().name());
    }

    public RefundAttemptResponse refund(RefundAttemptRequest request) {
        Payment payment = paymentRepository.findById(request.paymentId())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + request.paymentId()));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "payment not refundable in status " + payment.getStatus());
        }
        ChannelResult result = channel.refund(new RefundRequest(request.paymentId(), request.refundId(),
                request.amountMinor(), request.currencyCode(), "mock"));
        String mappedStatus = switch (result.status()) {
            case SUCCESS -> "SUCCEEDED";
            case FAILURE -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
        return new RefundAttemptResponse(request.refundId(), mappedStatus, result.channelReference());
    }
}
