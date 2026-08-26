package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

/**
 * 未知支付收敛（T039）：用查询/权威回调把 UNKNOWN 收敛为成功或失败，且只触发一次履约 RPC。
 *
 * <p>只有处于 {@link PaymentStatus#UNKNOWN} 的支付才被收敛；已终态视为幂等重复，
 * 不重复发布事件（履约只触发一次）。</p>
 */
@Service
public class PaymentUnknownResolutionService {

    private final PaymentRepository paymentRepository;
    private final PaymentResultProcessor processor;

    public PaymentUnknownResolutionService(PaymentRepository paymentRepository,
                                           PaymentResultProcessor processor) {
        this.paymentRepository = paymentRepository;
        this.processor = processor;
    }

    public boolean resolve(Long paymentId, ChannelResult authoritativeResult) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentId));
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return false;
        }
        return processor.applyAndPublish(paymentId, authoritativeResult);
    }
}
