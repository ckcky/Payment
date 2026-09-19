package com.payment.payment.mq;

import com.payment.common.mq.EventEnvelope;
import com.payment.common.mq.MqException;
import com.payment.common.mq.MqTopics;
import com.payment.common.mq.TransactionChecker;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * payment 侧消费路由 + 回查 checker（spec 029 / 批次 C / FR-301、FR-302 / T31、T49）。
 *
 * <p><b>消费</b>：{@code order.cancelled}（广播）→ 关闭本订单下未成功的支付单，
 * 拒收后续渠道回调（CLOSED 为终态，迟到结果被吸收）；已 SUCCEEDED 的支付单不关闭
 * （钱已收，多收由 order 侧 surplus 退款处置，INV-1 事实不回滚）。</p>
 *
 * <p><b>回查</b>：payment 生产两类事件，各配一个 checker——{@code payments} 是否 SUCCEEDED /
 * {@code refunds} 是否终态（去自己的库判事实，INV-4）。</p>
 */
@Component
public class PaymentMqHandlers {

    private static final Logger log = LoggerFactory.getLogger(PaymentMqHandlers.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public PaymentMqHandlers(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    // ---- 消费路由 ----

    /** 消费 {@code order.cancelled}：关闭本订单未成功的支付单（幂等）。 */
    void onOrderCancelled(EventEnvelope envelope) {
        String orderNo = envelope.str("orderNo");
        if (orderNo == null) {
            log.warn("order.cancelled 缺失 orderNo，忽略 msgId={}", envelope.msgId());
            return;
        }
        int closed = 0;
        for (Payment payment : paymentRepository.findByOrderNo(orderNo)) {
            if (payment.closeByOrderCancelled()) {
                paymentRepository.save(payment);
                closed++;
                log.info("订单取消关闭支付单 [orderNo={}, paymentNo={}, status={}]",
                        orderNo, payment.getPaymentNo(), payment.getStatus());
            }
        }
        log.info("MQ 消费 order.cancelled → payment [orderNo={}, closed={}, traceId={}]",
                orderNo, closed, envelope.traceId());
    }

    // ---- 回查 checker（T31）----

    /** {@code payment.succeeded} 回查：payments 是否 SUCCEEDED。 */
    TransactionChecker paymentSucceededChecker() {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return MqTopics.PAYMENT_SUCCEEDED.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                String paymentNo = e.str("paymentNo");
                if (paymentNo == null) {
                    return LocalTxState.ROLLBACK;
                }
                return paymentRepository.findByPaymentNo(paymentNo)
                        .map(p -> p.getStatus() == PaymentStatus.SUCCEEDED
                                || p.getStatus() == PaymentStatus.CLOSED
                                ? LocalTxState.COMMIT : LocalTxState.ROLLBACK)
                        .orElse(LocalTxState.ROLLBACK);
            }
        };
    }

    /** {@code refund.result} 回查：refunds 是否终态（SUCCEEDED / FAILED）。 */
    TransactionChecker refundResultChecker() {
        return new TransactionChecker() {
            @Override
            public boolean supports(EventEnvelope e) {
                return MqTopics.REFUND_RESULT.equals(e.topic());
            }

            @Override
            public LocalTxState check(EventEnvelope e) {
                String refundNo = e.str("paymentRefundNo");
                if (refundNo == null) {
                    return LocalTxState.ROLLBACK;
                }
                return refundRepository.findByRefundNo(refundNo)
                        .map(r -> isTerminal(r.getStatus())
                                ? LocalTxState.COMMIT : LocalTxState.ROLLBACK)
                        .orElse(LocalTxState.ROLLBACK);
            }
        };
    }

    private static boolean isTerminal(RefundStatus status) {
        return status == RefundStatus.SUCCEEDED || status == RefundStatus.FAILED;
    }

    /** 供生产侧读取支付单（富化负载用）。 */
    public Payment requirePayment(String paymentNo) {
        return paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new MqException("payment not found: " + paymentNo));
    }

    /** 供生产侧读取退款单（富化负载用）。 */
    public Refund requireRefund(String refundNo) {
        return refundRepository.findByRefundNo(refundNo)
                .orElseThrow(() -> new MqException("refund not found: " + refundNo));
    }
}
