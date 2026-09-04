package com.payment.payment.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自动退款服务（Feature 015 / P4 / INV-1）：订单 409 ORDER_NOT_PAYABLE → 原路退款。
 *
 * <p>语义（ADR-015）：</p>
 * <ul>
 *   <li>支付单保留 SUCCEEDED（钱确实收下过），退款是后续补偿动作而非状态回滚；</li>
 *   <li>退款幂等键 {@code autorefund:{paymentNo}}：同一支付单至多触发一次补偿语义，
 *       回调层 {@code changed=false} 的重复回写不会进入本服务；</li>
 *   <li>同步重试 3 次、指数退避 200ms 起（200/400/800ms），全部失败 →
 *       {@code payment.auto_refund_failed} 指标 + ERROR 日志转人工（对账兜底）。</li>
 * </ul>
 */
@Service
public class PaymentAutoRefundService implements AutoRefundGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentAutoRefundService.class);
    private static final String MODULE = "payment";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 200;

    private final PaymentRepository paymentRepository;
    private final PaymentRefundService refundService;
    private final BusinessMetrics metrics;

    public PaymentAutoRefundService(PaymentRepository paymentRepository,
                                    PaymentRefundService refundService,
                                    BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.metrics = metrics;
    }

    @Override
    public void autoRefund(String paymentNo, OrderNotPayableException cause) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo).orElse(null);
        if (payment == null || !payment.getStatus().name().equals("SUCCEEDED")) {
            log.warn("自动退款跳过：支付单非 SUCCEEDED paymentNo={} ", paymentNo);
            return;
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                RefundAttemptResponse response = refundService.refund(new RefundAttemptRequest(
                        payment.getId(), paymentNo, payment.getOrderNo(), payment.getUserId(),
                        payment.getAmountMinor(), payment.getCurrencyCode(),
                        "AUTO_REFUND:ORDER_NOT_PAYABLE",
                        "autorefund:" + paymentNo));
                metrics.counter("payment.auto_refund_succeeded", 1.0, "module", MODULE);
                log.warn("自动退款成功 paymentNo={} orderNo={} refundStatus={} channelRef={} reason={}",
                        paymentNo, cause.getOrderNo(), response.status(), response.channelReference(),
                        cause.getMessage());
                return;
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < MAX_ATTEMPTS) {
                    long wait = BACKOFF_MS * (1L << (attempt - 1)); // 200/400ms
                    log.warn("自动退款第 {} 次尝试失败，退避 {}ms 后重试 paymentNo={} err={}",
                            attempt, wait, paymentNo, ex.getMessage());
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        metrics.counter("payment.auto_refund_failed", 1.0, "module", MODULE);
        log.error("自动退款最终失败，转人工/对账兜底 paymentNo={} orderNo={} reason={}",
                paymentNo, cause.getOrderNo(), last == null ? "interrupted" : last.getMessage());
    }
}
