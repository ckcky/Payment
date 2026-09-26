package com.payment.payment.application.refund;

import com.payment.payment.domain.Payment;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderRepository;
import com.payment.channelgateway.domain.ChannelOrderStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.api.dto.RefundFactResponse;
import com.payment.payment.domain.Refund;
import com.payment.payment.domain.RefundRepository;
import com.payment.payment.domain.RefundStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 退款事实抽取（US3 对账）：暴露平台侧已确认（SUCCEEDED）的退款事实，
 * 供 reconciliation-service 拉取并与渠道账单逐笔核对。
 *
 * <p>Feature 016（FR-017 / N4 修复）：渠道引用改用<b>真实渠道退款流水号</b>——
 * 自退款渠道尝试记录（{@code channel_orders} 中 {@code attempt_type=REFUND} 的行，
 * 由 {@code PaymentRefundService} 在调渠道后落库）取得；废弃 {@code refund-{id}} 合成引用。
 * 仅有迁移前存量退款（无对应尝试记录）时回退 {@code refund-{id}} 并 WARN 留痕。</p>
 *
 * <p>spec 032 / C-22（H19）修复：渠道引用取<b>成功（SUCCEEDED）</b>的退款渠道尝试，
 * 并按 id 确定性排序——修复旧实现「无状态过滤取首条」导致 reference 可能指向失败尝试的问题。
 * spec 032 / H-032-1：补 merchantId（经 payment 反查）与 period 过滤。</p>
 */
@Service
public class RefundFactsService {

    private static final Logger log = LoggerFactory.getLogger(RefundFactsService.class);

    private final RefundRepository refundRepository;
    private final ChannelOrderRepository attemptRepository;
    private final PaymentRepository paymentRepository;

    public RefundFactsService(RefundRepository refundRepository,
                              ChannelOrderRepository attemptRepository,
                              PaymentRepository paymentRepository) {
        this.refundRepository = refundRepository;
        this.attemptRepository = attemptRepository;
        this.paymentRepository = paymentRepository;
    }

    /** 返回全部已确认成功的退款事实（无期间过滤，兼容入口）。 */
    public List<RefundFactResponse> confirmedFacts() {
        return confirmedFacts(null);
    }

    /** 返回已确认成功的退款事实；{@code period} 非空且为日期时按创建日期过滤。 */
    public List<RefundFactResponse> confirmedFacts(String period) {
        return filterByPeriod(refundRepository.findByStatus(RefundStatus.SUCCEEDED), period).stream()
                .map(this::toFact)
                .toList();
    }

    private List<Refund> filterByPeriod(List<Refund> facts, String period) {
        if (period == null || period.isBlank()) {
            return facts;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(period);
        } catch (RuntimeException e) {
            // demo/兼容周期串（非日期）：无法绑定 created_at，退化为全量 + WARN（不静默假装过滤）
            log.warn("refund confirmed-facts period not date-parsable, returning unfiltered: period={}", period);
            return facts;
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return refundRepository.findByStatusAndCreatedAtBetween(RefundStatus.SUCCEEDED, start, end);
    }

    private RefundFactResponse toFact(Refund r) {
        return new RefundFactResponse(r.getRefundNo(), resolveChannelReference(r), r.getAmountMinor(),
                r.getCurrencyCode(), r.getStatus().name(), resolveMerchantId(r));
    }

    /**
     * 渠道引用（C-22 / H19 精确化）：只认<b>成功</b>的退款渠道尝试，按 id 降序取最新一条
     * （重试多笔时确定性指向最终成功流水）；无任何成功尝试的存量退款回退合成引用并 WARN。
     */
    private String resolveChannelReference(Refund refund) {
        Optional<ChannelOrder> refundAttempt = attemptRepository.findByPaymentNo(refund.getPaymentNo())
                .stream()
                .filter(a -> ChannelOrder.TYPE_REFUND.equals(a.getAttemptType()))
                .filter(a -> a.getChannelReference() != null)
                .filter(a -> a.getStatus() == ChannelOrderStatus.SUCCEEDED)
                .max(Comparator.comparing(ChannelOrder::getId));
        return refundAttempt
                .map(ChannelOrder::getChannelReference)
                .orElseGet(() -> {
                    // 存量退款（Feature 016 之前）：无退款渠道尝试记录，回退 MVP 合成引用
                    return "refund-" + refund.getId();
                });
    }

    /** 商户归属（spec 032 / H-032-1）：经 payment 反查；反查不到（历史孤儿退款）返回 null。 */
    private String resolveMerchantId(Refund refund) {
        return paymentRepository.findByPaymentNo(refund.getPaymentNo())
                .map(Payment::getMerchantId)
                .orElse(null);
    }
}
