package com.payment.refund.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import org.springframework.stereotype.Service;

/**
 * 退款结果回调收敛（US2）：依据权威结果将 UNKNOWN 退款收敛为成功/失败。
 *
 * <p>终态冲突由领域状态机吸收（{@code succeed()/fail()/markUnknown()} 返回 false），
 * 因此重复/冲突的收敛天然幂等——只确认一次退款成功。</p>
 */
@Service
public class RefundRpcCallbackService {

    private final RefundRepository refundRepository;

    public RefundRpcCallbackService(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    /** 按业务单号收敛（ADR-0063）：权威结果端点不再接受数值主键。 */
    public Refund resolveRefund(String refundNo, String authoritativeStatus) {
        Refund refund = refundRepository.findByRefundNo(refundNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "refund not found: " + refundNo));

        switch (authoritativeStatus) {
            case "SUCCEEDED" -> refund.succeed();
            case "FAILED" -> refund.fail("resolved as failed");
            default -> refund.markUnknown("still unknown");
        }

        refundRepository.save(refund);
        return refund;
    }
}
