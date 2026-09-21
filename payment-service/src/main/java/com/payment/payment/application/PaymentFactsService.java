package com.payment.payment.application;

import com.payment.payment.api.dto.PaymentFactResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付事实抽取（US3 对账）：暴露平台侧已确认（SUCCEEDED）的支付事实，
 * 供 reconciliation-service 拉取并与渠道账单逐笔核对。
 *
 * <p>spec 032 / H-032-1：支持按期间（{@code period}）过滤——period 可解析为日期（YYYY-MM-DD）时，
 * 按 {@code DATE(created_at) = period} 过滤（跨期不重复结算 C-20 的事实侧锚点）；
 * period 缺省（兼容窗口）或不可解析为日期（demo 周期串）时返回全量并 WARN 留痕。</p>
 */
@Service
public class PaymentFactsService {

    private static final Logger log = LoggerFactory.getLogger(PaymentFactsService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;

    public PaymentFactsService(PaymentRepository paymentRepository,
                               PaymentAttemptRepository attemptRepository) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
    }

    /** 返回全部已确认成功的支付事实（无期间过滤，兼容入口）。 */
    public List<PaymentFactResponse> confirmedFacts() {
        return confirmedFacts(null);
    }

    /** 返回已确认成功的支付事实；{@code period} 非空且为日期时按创建日期过滤。 */
    public List<PaymentFactResponse> confirmedFacts(String period) {
        return filterByPeriod(paymentRepository.findByStatus(PaymentStatus.SUCCEEDED), period).stream()
                .map(this::toFact)
                .toList();
    }

    private List<Payment> filterByPeriod(List<Payment> facts, String period) {
        if (period == null || period.isBlank()) {
            return facts;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(period);
        } catch (RuntimeException e) {
            // demo/兼容周期串（非日期）：无法绑定 created_at，退化为全量 + WARN（不静默假装过滤）
            log.warn("confirmed-facts period not date-parsable, returning unfiltered facts: period={}", period);
            return facts;
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return paymentRepository.findByStatusAndCreatedAtBetween(PaymentStatus.SUCCEEDED, start, end);
    }

    private PaymentFactResponse toFact(Payment p) {
        String channelReference = p.getCurrentAttemptId() == null
                ? null
                : attemptRepository.findById(p.getCurrentAttemptId())
                        .map(attempt -> attempt.getChannelReference())
                        .orElse(null);
        return new PaymentFactResponse(p.getPaymentNo(), channelReference, p.getAmountMinor(),
                p.getCurrencyCode(), p.getStatus().name(), p.getMerchantId());
    }
}
