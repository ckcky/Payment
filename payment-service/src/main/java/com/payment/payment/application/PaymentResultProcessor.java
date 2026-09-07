package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 共享的「加载支付与当前尝试 → 应用渠道结果 → 持久化 → 支付指令编排」。
 * 回调（{@link PaymentCallbackService}）与未知收敛（{@link PaymentUnknownResolutionService}）复用，
 * 保证通知与记账只在支付真正迁移为成功时执行一次。
 *
 * <p>Feature 016（ADR-0054）职责归位：payment 退回<b>能力提供方</b>——支付成功后编排完成
 * 自身支付指令（渠道结果落 {@code payment_attempts} + 记账 {@code ledgerGateway.postPaymentCapture}），
 * 业务侧扇出<b>仅通知 order-service</b>（order 为业务编排者，由其 transaction 层判定正常/surplus
 * 并驱动履约 / 自动退款）。payment MUST NOT 直调 FulfillmentGateway 或 AutoRefundGateway。</p>
 */
@Component
public class PaymentResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultProcessor.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    private final BusinessMetrics metrics;

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @Autowired
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway,
                                  BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.metrics = metrics;
    }

    /** 兼容构造：不接账本时使用空记账网关（测试/账本未接入场景）。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  OrderGateway orderGateway) {
        this(paymentRepository, attemptRepository, orderGateway,
                (key, paymentId, amountMinor, feeMinor, currencyCode) -> {
                },
                new NoopBusinessMetrics());
    }

    /** 兼容构造：显式指定记账网关（测试场景），指标用空实现。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway) {
        this(paymentRepository, attemptRepository, orderGateway, ledgerGateway, new NoopBusinessMetrics());
    }

    /** 返回支付是否真正发生了状态迁移（据此决定是否已触发订单通知与记账）。 */
    public boolean applyAndNotify(String paymentNo, ChannelResult result) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentNo));
        PaymentAttempt attempt = attemptRepository.findById(payment.getCurrentAttemptId())
                .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR,
                        "payment attempt missing: " + payment.getCurrentAttemptId()));
        boolean changed = PaymentResultApplier.apply(payment, attempt, result);
        paymentRepository.save(payment);
        attemptRepository.save(attempt);
        if (changed && result.status() == ChannelResult.Status.SUCCESS) {
            PaymentSucceededRequest request = PaymentResultApplier.toSucceededRequest(payment);
            try {
                orderGateway.notifyPaymentSucceeded(request);
            } catch (RuntimeException ex) {
                // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）；
                // Feature 016：order 不再抛 409——surplus 判定与自动退款发起归 order transaction 层。
                // T109：不再静默吞异常——WARN + 指标留痕，供监控告警与对账兜底。
                log.warn("支付成功通知 order 失败（事实不回滚，对账兜底）paymentNo={} orderNo={} reason={}",
                        payment.getPaymentNo(), payment.getOrderNo(), ex.getMessage());
                metrics.counter("payment.order_notify_failed", 1.0, "module", "payment");
            }
            // 已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）；记账属 payment 层支付指令编排，
            // 保留在 payment 内（ADR-0054）。记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009）。
            // Feature 015 / C2：幂等键用 paymentNo 维度（PAYMENT:{paymentNo}），
            // 一交易多支付单时每张支付单独立记账，不再复用支付幂等键避免撞键静默少记账。
            ledgerGateway.postPaymentCapture("PAYMENT:" + payment.getPaymentNo(), payment.getPaymentNo(),
                    payment.getAmountMinor(), 0L, payment.getCurrencyCode());
        }
        return changed;
    }
}
