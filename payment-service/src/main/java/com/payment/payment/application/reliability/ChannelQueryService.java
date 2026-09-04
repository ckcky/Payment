package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

/**
 * UNKNOWN 主动查询收敛（spec US2 / ADR-0003）：周期性扫描 UNKNOWN 支付，向渠道查询权威状态，
 * 并据此收敛为成功/失败，且只触发一次下游动作。
 *
 * <p>行为要点：
 * <ul>
 *   <li>仅对未达查询上限（{@link ReliabilityConfig#getQueryMaxAttempts()}）的 UNKNOWN 支付查询；
 *       达到上限后停止自动查询，转人工/对账（spec 场景3）。</li>
 *   <li>渠道返回 SUCCESS/FAILURE 视为权威结果，复用 {@link PaymentUnknownResolutionService#resolve}
 *       收敛（终态冲突被吸收，保证「最多一次」下游动作，FR-004）。</li>
 *   <li>渠道返回 UNKNOWN（仍不明确）不猜成败，保持 UNKNOWN 等待后续查询/回调/对账。</li>
 * </ul>
 * 幂等与「最多一次」由支付状态机 + 收敛服务保证；乐观锁由仓储保护并发。</p>
 */
@Service
public class ChannelQueryService {

    private static final String MODULE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentChannel channel;
    private final PaymentUnknownResolutionService resolution;
    private final ReliabilityConfig config;
    private final BusinessMetrics metrics;

    public ChannelQueryService(PaymentRepository paymentRepository,
                               PaymentChannel channel,
                               PaymentUnknownResolutionService resolution,
                               ReliabilityConfig config,
                               BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.channel = channel;
        this.resolution = resolution;
        this.config = config;
        this.metrics = metrics;
    }

    /** 主动查询一轮：返回本轮收敛为终态的支付数量。 */
    public int queryRound() {
        int converged = 0;
        for (Payment payment : paymentRepository.findByStatus(PaymentStatus.UNKNOWN)) {
            if (payment.getQueryAttempts() >= config.getQueryMaxAttempts()) {
                continue; // 已达上限：停止自动查询，转人工/对账（FR-003 / spec 场景3）
            }
            payment.recordQueryAttempt();
            paymentRepository.save(payment);
            ChannelResult result = channel.queryStatus(
                    new QueryStatusRequest(payment.getPaymentNo(), payment.getTransactionId(), payment.getIdempotencyKey()));
            metrics.counter("payment.query", 1.0, "module", MODULE);
            if (result.status() != ChannelResult.Status.UNKNOWN) {
                if (resolution.resolve(String.valueOf(payment.getId()), result)) {
                    converged++;
                }
            } else if (payment.getQueryAttempts() >= config.getQueryMaxAttempts()) {
                metrics.counter("payment.query_exhausted", 1.0, "module", MODULE);
            }
        }
        return converged;
    }
}
