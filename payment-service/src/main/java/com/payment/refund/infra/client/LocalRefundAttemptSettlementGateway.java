package com.payment.refund.infra.client;

import com.payment.payment.application.RefundAttemptSettlementService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.refund.application.RefundAttemptSettlementGateway;
import org.springframework.stereotype.Component;

/**
 * refund 域 → payment 域的退款尝试收敛端口实现（进程内直调，同 {@link LocalPaymentRefundGateway} 模式）：
 * 尝试行状态机归属 payment 域，收敛动作由 {@link RefundAttemptSettlementService} 执行
 * （刻意不经 {@code PaymentRefundService}——后者依赖渠道 bean，会造成 bean 创建循环）。
 */
@Component
public class LocalRefundAttemptSettlementGateway implements RefundAttemptSettlementGateway {

    private final RefundAttemptSettlementService settlementService;

    public LocalRefundAttemptSettlementGateway(RefundAttemptSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Override
    public void convergeToTerminal(String paymentNo, String channelReference, ChannelResult outcome) {
        settlementService.convergeRefundAttempt(paymentNo, channelReference, outcome);
    }
}
