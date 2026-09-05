package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.refund.application.CreateRefundCommand;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.domain.Refund;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自动退款执行器（Feature 016 / ADR-0054）：被 order transaction 层经
 * {@code POST /internal/payments/refund-command} 调用的<b>命令执行入口</b>。
 *
 * <p>职责归位后 payment 不再自行判定 surplus（旧 {@code AutoRefundGateway} catch-409 语义已删除），
 * 仅按 order 的命令执行退款，走退款域既有生命周期（{@link RefundApplicationService#createRefund}：
 * 幂等回查 + intake lock + 防超退），即 FR-017 三步链——①生成 refundNo →
 * ②落退款渠道尝试记录（{@code PaymentRefundService}）→ ③调外部渠道三态收敛。</p>
 *
 * <ul>
 *   <li>退款幂等键 {@code autorefund:{transactionNo}:{paymentNo}}（FR-005）：同一
 *       (transactionNo, paymentNo) 重复触发幂等吸收；</li>
 *   <li>同步重试 3 次、指数退避 200ms 起（200/400/800ms），全部失败 →
 *       {@code payment.auto_refund_failed} 指标 + ERROR 日志转人工（对账兜底）；</li>
 *   <li>支付单保留 SUCCEEDED 事实不回滚（钱确实收下过，退款是补偿动作）。</li>
 * </ul>
 */
@Service
public class PaymentAutoRefundService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAutoRefundService.class);
    private static final String MODULE = "payment";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 200;

    private final PaymentRepository paymentRepository;
    private final RefundApplicationService refundApplicationService;
    private final BusinessMetrics metrics;

    public PaymentAutoRefundService(PaymentRepository paymentRepository,
                                    RefundApplicationService refundApplicationService,
                                    BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.refundApplicationService = refundApplicationService;
        this.metrics = metrics;
    }

    /**
     * 执行 order transaction 层发起的自动退款命令。
     *
     * @return 退款单号与退款单终态（SUCCEEDED / FAILED / UNKNOWN / REJECTED）
     */
    public RefundCommandResponse refundByOrder(RefundCommandRequest command) {
        Payment payment = paymentRepository.findByPaymentNo(command.paymentNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "payment not found: " + command.paymentNo()));
        if (!payment.getStatus().name().equals("SUCCEEDED")) {
            log.warn("自动退款跳过：支付单非 SUCCEEDED paymentNo={} status={}",
                    command.paymentNo(), payment.getStatus());
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "payment not refundable in status " + payment.getStatus());
        }
        String idempotencyKey = "autorefund:" + command.transactionNo() + ":" + command.paymentNo();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Refund refund = refundApplicationService.createRefund(
                        new CreateRefundCommand(
                                command.orderNo(), command.paymentNo(), command.userId(),
                                command.amountMinor(), command.currencyCode(),
                                "AUTO_REFUND:SURPLUS", idempotencyKey, List.of(),
                                command.transactionNo()));
                metrics.counter("payment.auto_refund_succeeded", 1.0, "module", MODULE);
                log.warn("自动退款命令执行完成 transactionNo={} paymentNo={} orderNo={} refundNo={} status={}",
                        command.transactionNo(), command.paymentNo(), command.orderNo(),
                        refund.getRefundNo(), refund.getStatus());
                return new RefundCommandResponse(refund.getRefundNo(), refund.getStatus().name());
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < MAX_ATTEMPTS) {
                    long wait = BACKOFF_MS * (1L << (attempt - 1)); // 200/400ms
                    log.warn("自动退款第 {} 次尝试失败，退避 {}ms 后重试 transactionNo={} paymentNo={} err={}",
                            attempt, wait, command.transactionNo(), command.paymentNo(), ex.getMessage());
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
        log.error("自动退款最终失败，转人工/对账兜底 transactionNo={} paymentNo={} orderNo={} reason={}",
                command.transactionNo(), command.paymentNo(), command.orderNo(),
                last == null ? "interrupted" : last.getMessage());
        throw last != null ? last : BizException.of(ErrorCodes.INTERNAL_ERROR, "auto refund failed");
    }
}
