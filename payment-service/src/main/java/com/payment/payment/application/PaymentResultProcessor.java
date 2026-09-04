package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 共享的「加载支付与当前尝试 → 应用渠道结果 → 持久化 → 按需触发一次履约 RPC」编排。
 * 回调（{@link PaymentCallbackService}）与未知收敛（{@link PaymentUnknownResolutionService}）复用，
 * 保证履约只在支付真正迁移为成功时触发一次。
 */
@Component
public class PaymentResultProcessor {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final FulfillmentGateway fulfillmentGateway;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    /** 自动退款网关（Feature 015 / P4）：缺省 no-op，生产由 Spring 注入 {@code PaymentAutoRefundService}。 */
    private AutoRefundGateway autoRefundGateway = (paymentNo, cause) -> { };

    /** 自动退款网关注入（可选依赖：单测手工构造时可缺省）。 */
    @Autowired(required = false)
    void setAutoRefundGateway(AutoRefundGateway autoRefundGateway) {
        this.autoRefundGateway = autoRefundGateway;
    }

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @Autowired
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  FulfillmentGateway fulfillmentGateway,
                                  OrderGateway orderGateway,
                                  LedgerPostingGateway ledgerGateway) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.fulfillmentGateway = fulfillmentGateway;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
    }

    /** 兼容构造：不接账本时使用空记账网关（测试/账本未接入场景）。 */
    public PaymentResultProcessor(PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  FulfillmentGateway fulfillmentGateway,
                                  OrderGateway orderGateway) {
        this(paymentRepository, attemptRepository, fulfillmentGateway, orderGateway,
                (key, paymentId, amountMinor, feeMinor, currencyCode) -> {
                });
    }

    /** 返回支付是否真正发生了状态迁移（据此决定是否已触发履约 RPC）。 */
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
                fulfillmentGateway.notifyPaymentSucceeded(request);
            } catch (RuntimeException ignored) {
                // 履约 RPC 失败不得回滚支付成功事实（跨服务一致性由幂等 + 后续对账收敛）。
            }
            try {
                orderGateway.notifyPaymentSucceeded(request);
            } catch (OrderNotPayableException ex) {
                // Feature 015 / C5 / INV-1：订单已不可支付（取消/超时/关闭）却收到支付成功回写，
                // 钱已收下必须原路退回 → 触发自动退款（P4），保留支付单 SUCCEEDED 事实不回滚。
                autoRefundGateway.autoRefund(payment.getPaymentNo(), ex);
            } catch (RuntimeException ignored) {
                // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）。
            }
            // 已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）；
            // 记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009）。
            // Feature 015 / C2：幂等键改用 paymentNo 维度（PAYMENT:{paymentNo}），
            // 一交易多支付单时每张支付单独立记账，不再复用支付幂等键避免撞键静默少记账。
            ledgerGateway.postPaymentCapture("PAYMENT:" + payment.getPaymentNo(), payment.getId(),
                    payment.getAmountMinor(), 0L, payment.getCurrencyCode());
        }
        return changed;
    }
}
