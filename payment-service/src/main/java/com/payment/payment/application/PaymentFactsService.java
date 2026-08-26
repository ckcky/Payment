package com.payment.payment.application;

import com.payment.payment.api.dto.PaymentFactResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 支付事实抽取（US3 对账）：暴露平台侧已确认（SUCCEEDED）的支付事实，
 * 供 reconciliation-service 拉取并与渠道账单逐笔核对。
 */
@Service
public class PaymentFactsService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;

    public PaymentFactsService(PaymentRepository paymentRepository,
                               PaymentAttemptRepository attemptRepository) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
    }

    /** 返回全部已确认成功的支付事实，渠道引用取当前尝试的渠道引用（若无尝试则为 null）。 */
    public List<PaymentFactResponse> confirmedFacts() {
        return paymentRepository.findByStatus(PaymentStatus.SUCCEEDED).stream()
                .map(this::toFact)
                .toList();
    }

    private PaymentFactResponse toFact(Payment p) {
        String channelReference = p.getCurrentAttemptId() == null
                ? null
                : attemptRepository.findById(p.getCurrentAttemptId())
                        .map(attempt -> attempt.getChannelReference())
                        .orElse(null);
        return new PaymentFactResponse(p.getId(), channelReference, p.getAmountMinor(),
                p.getCurrencyCode(), p.getStatus().name());
    }
}
