package com.payment.refund.application;

import com.payment.refund.api.dto.RefundFactResponse;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 退款事实抽取（US3 对账）：暴露平台侧已确认（SUCCEEDED）的退款事实，
 * 供 reconciliation-service 拉取并与渠道账单逐笔核对。
 *
 * <p>MVP 约定：退款事实以 {@code refund-{id}} 作为外部引用，与渠道账单 fixture 对齐。</p>
 */
@Service
public class RefundFactsService {

    private final RefundRepository refundRepository;

    public RefundFactsService(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    /** 返回全部已确认成功的退款事实。 */
    public List<RefundFactResponse> confirmedFacts() {
        return refundRepository.findByStatus(RefundStatus.SUCCEEDED).stream()
                .map(this::toFact)
                .toList();
    }

    private RefundFactResponse toFact(Refund r) {
        String channelReference = "refund-" + r.getId();
        return new RefundFactResponse(r.getId(), channelReference, r.getAmountMinor(),
                r.getCurrencyCode(), r.getStatus().name());
    }
}
