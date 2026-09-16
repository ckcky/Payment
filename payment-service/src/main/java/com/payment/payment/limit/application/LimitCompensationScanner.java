package com.payment.payment.limit.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.infra.LimitProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 额度结算补偿扫描（spec 027 / FR-016、FR-017，ADR-0071 D4 第 3 条）。
 *
 * <p><b>为什么必须有它</b>（D4）：额度结算运行在支付状态迁移<b>之外</b>（结算失败不回滚支付事实，
 * 见 ADR-0009 哲学）。因此存在「支付已 {@code SUCCEEDED} 但 {@code used} 未加」的窗口——
 * 额度虚高、<b>限额静默失效</b>。这比报错危险得多：钱收了，限额不知道。故需要一个以
 * <b>payment 为事实源</b>的收敛器。</p>
 *
 * <p><b>扫描口径</b>：「payment 已终态但只有 {@code RESERVE}、无任何终态流水」。因
 * {@code UK(biz_no, op_type, period)}（FR-015），重复扫描天然幂等——第二次扫到时结算流水已存在，
 * 金额不再变更（SC-006）。</p>
 *
 * <p><b>跳过 UNKNOWN</b>（FR-017）：保守占用，守「未知状态不猜成败」（Constitution §3）。
 * UNKNOWN 的 {@code pending} 由 TTL 惰性回收（D13）或人工收敛处理，不在这里猜。</p>
 *
 * <p><b>与惰性回收的分工</b>：本扫描只处理「payment 已终态」的，回收只处理「payment 未终态
 * 但 TTL 到期」的。两者以 {@code EXPIRED} 流水互斥（补偿扫描跳过已有终态流水的记录），
 * 不会重复释放。</p>
 */
@Service
public class LimitCompensationScanner {

    private static final Logger log = LoggerFactory.getLogger(LimitCompensationScanner.class);

    /** 单轮扫描上限：避免一次拉爆内存（与既有 TimeoutScanner 同纪律）。 */
    private static final int BATCH_LIMIT = 200;

    private final PaymentRepository paymentRepository;
    private final LimitOperationRepository operationRepository;
    private final LimitSettlementService settlementService;
    private final LimitProperties properties;
    private final BusinessMetrics metrics;

    public LimitCompensationScanner(PaymentRepository paymentRepository,
                                    LimitOperationRepository operationRepository,
                                    LimitSettlementService settlementService,
                                    LimitProperties properties,
                                    BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.operationRepository = operationRepository;
        this.settlementService = settlementService;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 扫一轮并补齐缺失的结算。
     *
     * @return 本轮补齐的笔数
     */
    public int scan(Instant now) {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<Payment> candidates = findCandidates();
        if (candidates.isEmpty()) {
            return 0;
        }
        int compensated = 0;
        for (Payment payment : candidates) {
            try {
                boolean succeeded = payment.getStatus() == PaymentStatus.SUCCEEDED;
                boolean settled = settlementService.settleByPayment(
                        payment.getPaymentNo(), payment.getUserId(), payment.getCurrencyCode(),
                        payment.getAmountMinor(), succeeded);
                if (settled) {
                    compensated++;
                    metrics.counter("payment.limit_compensated", 1.0,
                            "kind", succeeded ? "confirm" : "release");
                    log.info("额度结算补偿完成 paymentNo={} status={} amount={}",
                            payment.getPaymentNo(), payment.getStatus(), payment.getAmountMinor());
                }
            } catch (RuntimeException e) {
                // 单笔失败不拖垮整轮；下一轮会再扫到（幂等）
                log.warn("额度结算补偿失败，留待下一轮 paymentNo={} reason={}",
                        payment.getPaymentNo(), e.getMessage());
                metrics.counter("payment.limit_compensation_failed", 1.0);
            }
        }
        return compensated;
    }

    /**
     * 找出「payment 已终态但额度未结算」的记录。
     *
     * <p><b>为什么不写一条 JOIN SQL</b>：跨表 JOIN 会绕过仓储边界（ArchUnit INV-5 的精神，
     * 且 {@code payments} 归 payment 层、{@code limit_operations} 归限额子域）。这里改为
     * 「先按终态取支付单（走既有 {@code findByStatus}）→ 再逐笔查是否已有终态流水」——
     * 每轮最多 {@value #BATCH_LIMIT} 笔，且绝大多数笔已有终态流水，判定是主键/唯一键级查询。</p>
     *
     * <p>性能取舍的依据：这是 30s 一轮的后台收敛器，不是请求路径；而支付单总量在
     * 教学项目规模下有限，且 {@code findByStatus} 走的是 status 索引。</p>
     */
    private List<Payment> findCandidates() {
        List<Payment> candidates = new ArrayList<>();
        for (PaymentStatus terminal : List.of(PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED, PaymentStatus.CLOSED)) {
            // 注意：UNKNOWN 不在列表中——FR-017 明确跳过
            for (Payment payment : paymentRepository.findByStatus(terminal)) {
                if (candidates.size() >= BATCH_LIMIT) {
                    return candidates;
                }
                if (!isSettled(payment.getPaymentNo())) {
                    candidates.add(payment);
                }
            }
        }
        return candidates;
    }

    /**
     * 该支付单是否已有终态额度流水。
     *
     * <p>「有 {@code RESERVE} 才需要补偿」——完全没有限额流水的支付单（无配置用户）跳过，
     * 避免为不限额用户白跑三轮查询。</p>
     */
    private boolean isSettled(String paymentNo) {
        if (!operationRepository.exists(paymentNo, LimitOperationType.RESERVE, null)) {
            return true;    // 没有预占过（不限额用户）→ 无需补偿，视为「已了结」
        }
        return settlementService.isSettled(paymentNo);
    }

    /** 诊断快照（演示与运维用）：当前有多少笔待补偿。 */
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("pendingCandidates", findCandidates().size());
        result.put("reserveTtl", properties.getReserveTtl().toString());
        result.put("scanBatchLimit", BATCH_LIMIT);
        return result;
    }
}
