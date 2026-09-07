package com.payment.refund.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import org.springframework.stereotype.Service;

/**
 * 退款结果回调收敛（US2 / spec 019 T108）：异步渠道回调与人工 resolve 两路，
 * 全部经 {@link RefundResultProcessor} 统一后处理（状态机终态 → 记账冲正 → 通知 order）。
 *
 * <p>终态冲突由领域状态机吸收（{@code succeed()/fail()/markUnknown()} 返回 false），
 * 因此重复/冲突的收敛天然幂等——只确认一次退款成功、只记一次账、只通知一次（重放被吸收）。</p>
 */
@Service
public class RefundRpcCallbackService {

    private final RefundRepository refundRepository;
    private final RefundResultProcessor resultProcessor;

    public RefundRpcCallbackService(RefundRepository refundRepository,
                                    RefundResultProcessor resultProcessor) {
        this.refundRepository = refundRepository;
        this.resultProcessor = resultProcessor;
    }

    /**
     * 渠道异步退款回调（spec 019 / ADR-0067）：渠道受理后延迟推送权威结果。
     * 按业务单号 PMRF 寻址；验签防重放由 {@code ChannelCallbackSignatureFilter} 前置。
     */
    public Refund handleChannelCallback(String refundNo, ChannelResult result) {
        Refund refund = requireRefund(refundNo);
        return resultProcessor.apply(refund, result, RefundResultProcessor.Source.CHANNEL_CALLBACK);
    }

    /** 按业务单号人工收敛（ADR-0063）：权威结果端点不接受数值主键。 */
    public Refund resolveRefund(String refundNo, String authoritativeStatus) {
        Refund refund = requireRefund(refundNo);
        ChannelResult outcome = switch (authoritativeStatus) {
            case "SUCCEEDED" -> ChannelResult.success(null);
            case "FAILED" -> ChannelResult.businessFailure(null, "resolved as failed");
            default -> ChannelResult.businessUnknown("still unknown");
        };
        return resultProcessor.apply(refund, outcome, RefundResultProcessor.Source.RESOLVE);
    }

    private Refund requireRefund(String refundNo) {
        return refundRepository.findByRefundNo(refundNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "refund not found: " + refundNo));
    }
}
