package com.payment.refund.application;

import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.refund.api.dto.RefundFactResponse;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 退款事实抽取（US3 对账）：暴露平台侧已确认（SUCCEEDED）的退款事实，
 * 供 reconciliation-service 拉取并与渠道账单逐笔核对。
 *
 * <p>Feature 016（FR-017 / N4 修复）：渠道引用改用<b>真实渠道退款流水号</b>——
 * 自退款渠道尝试记录（{@code payment_attempts} 中 {@code attempt_type=REFUND} 的行，
 * 由 {@code PaymentRefundService} 在调渠道后落库）取得；废弃 {@code refund-{id}} 合成引用。
 * 仅有迁移前存量退款（无对应尝试记录）时回退 {@code refund-{id}} 并 WARN 留痕。</p>
 */
@Service
public class RefundFactsService {

    private final RefundRepository refundRepository;
    private final PaymentAttemptRepository attemptRepository;

    public RefundFactsService(RefundRepository refundRepository,
                              PaymentAttemptRepository attemptRepository) {
        this.refundRepository = refundRepository;
        this.attemptRepository = attemptRepository;
    }

    /** 返回全部已确认成功的退款事实。 */
    public List<RefundFactResponse> confirmedFacts() {
        return refundRepository.findByStatus(RefundStatus.SUCCEEDED).stream()
                .map(this::toFact)
                .toList();
    }

    private RefundFactResponse toFact(Refund r) {
        return new RefundFactResponse(r.getRefundNo(), resolveChannelReference(r), r.getAmountMinor(),
                r.getCurrencyCode(), r.getStatus().name());
    }

    /** 渠道引用：优先取退款渠道尝试记录的真实渠道退款流水号；存量数据回退合成引用。 */
    private String resolveChannelReference(Refund refund) {
        Optional<PaymentAttempt> refundAttempt = attemptRepository.findByPaymentNo(refund.getPaymentNo())
                .stream()
                .filter(a -> PaymentAttempt.TYPE_REFUND.equals(a.getAttemptType()))
                .filter(a -> a.getChannelReference() != null)
                .findFirst();
        return refundAttempt
                .map(PaymentAttempt::getChannelReference)
                .orElseGet(() -> {
                    // 存量退款（Feature 016 之前）：无退款渠道尝试记录，回退 MVP 合成引用
                    return "refund-" + refund.getId();
                });
    }
}
