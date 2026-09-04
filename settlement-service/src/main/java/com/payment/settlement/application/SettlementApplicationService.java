package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.settlement.domain.AdjustmentDirection;
import com.payment.settlement.domain.AdjustmentStatus;
import com.payment.settlement.domain.EligibilityDecision;
import com.payment.settlement.domain.SettlementAdjustment;
import com.payment.settlement.domain.SettlementAdjustmentRepository;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementEligibility;
import com.payment.settlement.domain.SettlementItem;
import com.payment.settlement.domain.SettlementRepository;
import com.payment.settlement.domain.SettlementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 结算批次编排（US3）：幂等受理、资格校验、净额计算、模拟执行与结果收敛。
 *
 * <p>幂等键与商户+周期均构成业务幂等，由数据库唯一约束兜底（{@link #insertNew} 捕获重复键回放）。
 * 净额 = 收入 − 退款 + 调整（ADR-0022：adjustment 为带符号合计）。执行仅模拟：批次直接进入 UNKNOWN，
 * 交由 {@link #resolveBatch} 依据权威结果收敛，绝不臆断成败。</p>
 *
 * <p>ADR-0023 纵深防御：建批前经 {@link ConfirmedFactGate} 逐条强制校验（未确认事实不得结算）；
 * 幂等键命中后校验商户/周期一致性（N5）；收敛为 SUCCEEDED 且净额 > 0 时经 {@link LedgerPostingGateway}
 * 记账（满足 Constitution §II.3「一切资金变动 MUST 经 ledger」）。</p>
 */
@Service
public class SettlementApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementApplicationService.class);
    private static final String CURRENCY = "CNY";

    private final SettlementRepository settlementRepository;
    private final MerchantClient merchantClient;
    private final ReconciliationClient reconciliationClient;
    private final SettlementAdjustmentRepository adjustmentRepository;
    private final LedgerPostingGateway ledgerPostingGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public SettlementApplicationService(SettlementRepository settlementRepository,
                                        MerchantClient merchantClient,
                                        ReconciliationClient reconciliationClient,
                                        SettlementAdjustmentRepository adjustmentRepository,
                                        LedgerPostingGateway ledgerPostingGateway,
                                        BusinessMetrics metrics,
                                        StructuredAuditLogger auditLogger) {
        this.settlementRepository = settlementRepository;
        this.merchantClient = merchantClient;
        this.reconciliationClient = reconciliationClient;
        this.adjustmentRepository = adjustmentRepository;
        this.ledgerPostingGateway = ledgerPostingGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    @Transactional
    public SettlementBatch createBatch(String merchantId, String period, String idempotencyKey) {
        // N5：幂等键命中后校验商户/周期一致性，不一致报 DUPLICATE（MUST NOT 静默返回他商户/他周期批次）。
        Optional<SettlementBatch> byKey = settlementRepository.findByIdempotencyKey(idempotencyKey);
        if (byKey.isPresent()) {
            SettlementBatch existing = byKey.get();
            if (!existing.getMerchantId().equals(merchantId) || !existing.getPeriod().equals(period)) {
                throw BizException.of(ErrorCodes.DUPLICATE,
                        "idempotency key reused for different merchant/period");
            }
            return existing;
        }
        Optional<SettlementBatch> byMerchantPeriod = settlementRepository.findByMerchantAndPeriod(merchantId, period);
        if (byMerchantPeriod.isPresent()) {
            return byMerchantPeriod.get();
        }

        MerchantView merchant = merchantClient.getMerchant(Long.valueOf(merchantId));
        boolean merchantActiveAndEligible = "ACTIVE".equals(merchant.status()) && merchant.settlementEligible();

        ReconciliationSummary summary = reconciliationClient.getSettlementSummary(period);

        // ADR-0023 闸门：本地逐条强制校验未确认事实，不通过则抛异常、不落任何批次。
        ConfirmedFactGate.gate(summary, CURRENCY, period, metrics);

        EligibilityDecision decision = SettlementEligibility.evaluate(
                merchantActiveAndEligible, summary.unresolvedDifferenceCount());
        if (!decision.eligible()) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, decision.reason());
        }

        long income = summary.facts().stream()
                .filter(f -> "PAYMENT".equals(f.type()))
                .mapToLong(SettlementFact::amountMinor)
                .sum();
        long refund = summary.facts().stream()
                .filter(f -> "REFUND".equals(f.type()))
                .mapToLong(SettlementFact::amountMinor)
                .sum();

        // ADR-0022：先于批次登记的 ACTIVE 调整项，带符号合计进入净额公式。
        List<SettlementAdjustment> activeAdjustments =
                adjustmentRepository.findActiveByMerchantAndPeriod(merchantId, period);
        long signedAdjustment = activeAdjustments.stream()
                .mapToLong(SettlementAdjustment::signedAmountMinor)
                .sum();

        SettlementBatch batch = new SettlementBatch(merchantId, period, CURRENCY, idempotencyKey);
        batch.calculate(income, refund, signedAdjustment, CURRENCY);
        if (batch.getNetMinor() < 0) {
            metrics.counter("settlement.negative_net", 1, "module", "settlement");
        }
        batch.markReady();
        for (SettlementFact fact : summary.facts()) {
            batch.addItem(new SettlementItem(fact.reference(), fact.type(), fact.amountMinor(), fact.currencyCode()));
        }
        for (SettlementAdjustment adjustment : activeAdjustments) {
            batch.addItem(new SettlementItem(adjustment.getIdempotencyKey(), "ADJUSTMENT",
                    adjustment.signedAmountMinor(), adjustment.getCurrencyCode()));
        }
        batch.recordSource(summary.facts().size(), period);

        batch = insertNew(batch);

        metrics.counter("settlement.batch_initiated", 1, "module", "settlement");
        auditLogger.audit("settlement.batch_initiated", batch.getIdempotencyKey(), batch.getNetMinor(),
                batch.getCurrencyCode(), null, SettlementStatus.READY.name(), "settlement",
                String.valueOf(batch.getId()));

        // 模拟执行：无真实打款，执行结果直接置 UNKNOWN（未知执行结果不等于成功）。
        batch.execute();
        batch.markUnknown("mock settlement payout unknown");
        settlementRepository.save(batch);

        metrics.counter("settlement.unknown", 1, "module", "settlement");
        auditLogger.audit("settlement.unknown", batch.getIdempotencyKey(), batch.getNetMinor(),
                batch.getCurrencyCode(), SettlementStatus.EXECUTING.name(), SettlementStatus.UNKNOWN.name(),
                "settlement", String.valueOf(batch.getId()));

        return batch;
    }

    /**
     * 登记结算调整项（ADR-0022）：必须先于批次登记；批次已存在则拒绝（快照语义不可追溯篡改）。
     * 同键同参返回首次；同键不同参报 DUPLICATE（MUST NOT 静默覆盖）。
     */
    @Transactional
    public SettlementAdjustment registerAdjustment(String merchantId, String period, String idempotencyKey,
                                                   long amountMinor, AdjustmentDirection direction,
                                                   String currencyCode, String reason, String operator) {
        // 批次已是该 (merchant, period) 的事实快照，建批后禁止追登调整项。
        if (settlementRepository.findByMerchantAndPeriod(merchantId, period).isPresent()) {
            metrics.counter("settlement.adjustment_rejected", 1, "module", "settlement", "reason", "batch_exists");
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "settlement batch already exists for merchant " + merchantId + " period " + period);
        }
        // 币种一致性（MVP 仅 CNY），MUST NOT 静默混算。
        if (!CURRENCY.equals(currencyCode)) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "adjustment currency must be " + CURRENCY);
        }
        Optional<SettlementAdjustment> existing = adjustmentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            SettlementAdjustment e = existing.get();
            boolean sameParams = e.getMerchantId().equals(merchantId)
                    && e.getPeriod().equals(period)
                    && e.getAmountMinor() == amountMinor
                    && e.getDirection() == direction
                    && e.getCurrencyCode().equals(currencyCode)
                    && e.getReason().equals(reason)
                    && e.getOperator().equals(operator);
            if (!sameParams) {
                throw BizException.of(ErrorCodes.DUPLICATE, "adjustment idempotency key reused with different params");
            }
            return e;
        }

        SettlementAdjustment adjustment = new SettlementAdjustment(
                idempotencyKey, merchantId, period, amountMinor, direction, currencyCode, reason, operator);
        adjustment = adjustmentRepository.save(adjustment);

        metrics.counter("settlement.adjustment_registered", 1, "module", "settlement");
        auditLogger.audit("settlement.adjustment_registered", idempotencyKey, amountMinor, currencyCode,
                null, AdjustmentStatus.ACTIVE.name(), "settlement", merchantId + "/" + period);
        return adjustment;
    }

    public SettlementBatch getBatch(Long id) {
        return requireBatch(id);
    }

    /**
     * 列出批次（ADR-0023 GET /batches）：按可选 merchantId / period 过滤。
     */
    public List<SettlementBatch> listBatches(String merchantId, String period) {
        return settlementRepository.listBatches(merchantId, period);
    }

    @Transactional
    public SettlementBatch resolveBatch(Long id, String authoritativeStatus) {
        SettlementBatch batch = requireBatch(id);
        switch (authoritativeStatus) {
            case "SUCCEEDED" -> {
                batch.succeed();
                // ADR-0023：收敛为成功且净额 > 0 时记账；net <= 0 不发起（账本要求分录金额 > 0）。
                if (batch.getNetMinor() > 0) {
                    ledgerPostingGateway.postSettlement(batch.getIdempotencyKey(), batch.getId(),
                            batch.getNetMinor(), batch.getCurrencyCode());
                } else {
                    metrics.counter("settlement.ledger_skip_nonpositive_net", 1, "module", "settlement");
                    log.info("结算净额非正，跳过记账 batchId={} net={}", batch.getId(), batch.getNetMinor());
                }
            }
            case "FAILED" -> {
                String fromStatus = batch.getStatus().name();
                batch.fail("resolved failed");
                metrics.counter("settlement.failed", 1, "module", "settlement");
                auditLogger.audit("settlement.failed", batch.getIdempotencyKey(), batch.getNetMinor(),
                        batch.getCurrencyCode(), fromStatus, SettlementStatus.FAILED.name(),
                        "settlement", String.valueOf(batch.getId()));
            }
            default -> batch.markUnknown("still unknown");
        }
        settlementRepository.save(batch);
        return batch;
    }

    /** 关闭批次（ADR-0023 POST /batches/{id}/close）：仅成功/失败态可关闭为终态。 */
    @Transactional
    public SettlementBatch closeBatch(Long id, String operator) {
        SettlementBatch batch = requireBatch(id);
        SettlementStatus before = batch.getStatus();
        batch.close();
        settlementRepository.save(batch);
        metrics.counter("settlement.batch_closed", 1, "module", "settlement");
        auditLogger.audit("settlement.batch_closed", batch.getIdempotencyKey(), batch.getNetMinor(),
                batch.getCurrencyCode(), before.name(), SettlementStatus.CLOSED.name(),
                "settlement", String.valueOf(batch.getId()));
        return batch;
    }

    private SettlementBatch requireBatch(Long id) {
        return settlementRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "settlement batch not found: " + id));
    }

    private SettlementBatch insertNew(SettlementBatch batch) {
        try {
            return settlementRepository.save(batch);
        } catch (DuplicateKeyException e) {
            return settlementRepository.findByIdempotencyKey(batch.getIdempotencyKey())
                    .or(() -> settlementRepository.findByMerchantAndPeriod(batch.getMerchantId(), batch.getPeriod()))
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "settlement batch duplicate: " + batch.getIdempotencyKey()));
        }
    }
}
