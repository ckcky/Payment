package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.idempotency.IdempotencyKey;
import com.payment.common.core.idempotency.IdempotencyRegistry;
import com.payment.settlement.domain.EligibilityDecision;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementEligibility;
import com.payment.settlement.domain.SettlementItem;
import com.payment.settlement.domain.SettlementRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 结算批次编排（US3）：幂等受理、资格校验、净额计算、模拟执行与结果收敛。
 *
 * <p>幂等键以 {@code settlement:create} 作用域登记，且商户+周期构成业务幂等（数据库唯一约束兜底）。
 * 净额 = 收入 − 退款 − 调整（MVP 调整额为 0）。执行仅模拟：批次直接进入 UNKNOWN，
 * 交由 {@link #resolveBatch} 依据权威结果收敛，绝不臆断成败。</p>
 */
@Service
public class SettlementApplicationService {

    private static final String IDEMPOTENCY_SCOPE = "settlement:create";

    private final SettlementRepository settlementRepository;
    private final MerchantClient merchantClient;
    private final ReconciliationClient reconciliationClient;
    private final IdempotencyRegistry idempotencyRegistry;

    public SettlementApplicationService(SettlementRepository settlementRepository,
                                        MerchantClient merchantClient,
                                        ReconciliationClient reconciliationClient,
                                        IdempotencyRegistry idempotencyRegistry) {
        this.settlementRepository = settlementRepository;
        this.merchantClient = merchantClient;
        this.reconciliationClient = reconciliationClient;
        this.idempotencyRegistry = idempotencyRegistry;
    }

    public SettlementBatch createBatch(String merchantId, String period, String idempotencyKey) {
        IdempotencyKey key = IdempotencyKey.of(IDEMPOTENCY_SCOPE, idempotencyKey);
        Optional<String> existing = idempotencyRegistry.find(key);
        if (existing.isPresent()) {
            return requireBatch(Long.valueOf(existing.get()));
        }

        Optional<SettlementBatch> byMerchantPeriod = settlementRepository.findByMerchantAndPeriod(merchantId, period);
        if (byMerchantPeriod.isPresent()) {
            return byMerchantPeriod.get();
        }

        MerchantView merchant = merchantClient.getMerchant(Long.valueOf(merchantId));
        boolean merchantActiveAndEligible = "ACTIVE".equals(merchant.status()) && merchant.settlementEligible();

        ReconciliationSummary summary = reconciliationClient.getSettlementSummary(period);

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

        SettlementBatch batch = new SettlementBatch(merchantId, period, "CNY", idempotencyKey);
        batch.calculate(income, refund, 0, "CNY");
        batch.markReady();
        for (SettlementFact fact : summary.facts()) {
            batch.addItem(new SettlementItem(fact.reference(), fact.type(), fact.amountMinor(), fact.currencyCode()));
        }

        settlementRepository.save(batch);
        if (!idempotencyRegistry.recordIfAbsent(key, String.valueOf(batch.getId()))) {
            String winnerId = idempotencyRegistry.find(key)
                    .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR, "idempotency race"));
            return requireBatch(Long.valueOf(winnerId));
        }

        // 模拟执行：无真实打款，执行结果直接置 UNKNOWN（未知执行结果不等于成功）。
        batch.execute();
        batch.markUnknown("mock settlement payout unknown");
        settlementRepository.save(batch);

        return batch;
    }

    public SettlementBatch getBatch(Long id) {
        return requireBatch(id);
    }

    public SettlementBatch resolveBatch(Long id, String authoritativeStatus) {
        SettlementBatch batch = requireBatch(id);
        switch (authoritativeStatus) {
            case "SUCCEEDED" -> batch.succeed();
            case "FAILED" -> batch.fail("resolved failed");
            default -> batch.markUnknown("still unknown");
        }
        settlementRepository.save(batch);
        return batch;
    }

    private SettlementBatch requireBatch(Long id) {
        return settlementRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "settlement batch not found: " + id));
    }
}
