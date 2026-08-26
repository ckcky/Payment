package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.idempotency.IdempotencyKey;
import com.payment.common.core.idempotency.IdempotencyRegistry;
import com.payment.reconciliation.api.ReconciliationSettlementFact;
import com.payment.reconciliation.api.ReconciliationSettlementSummaryResponse;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationMatching;
import com.payment.reconciliation.domain.ReconciliationMatchingResult;
import com.payment.reconciliation.domain.ReconciliationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对账编排（US3）：拉取平台 Payment/Refund 已确认事实与渠道账单，逐笔比对，
 * 产出对账批次（匹配 + 差异）。只读平台事实，绝不修改原始 Payment/Refund。
 *
 * <p>幂等键以 {@code reconciliation:run} 作用域登记：同一周期重复执行返回首次批次，
 * 不重复比对该周期。</p>
 */
@Service
public class ReconciliationApplicationService {

    private static final String IDEMPOTENCY_SCOPE = "reconciliation:run";
    private static final String MOCK_CHANNEL_SOURCE = "mock-channel";

    private final ReconciliationRepository repository;
    private final PaymentFactsClient paymentFactsClient;
    private final RefundFactsClient refundFactsClient;
    private final IdempotencyRegistry idempotencyRegistry;
    private final ChannelStatementLoader channelStatementLoader;

    public ReconciliationApplicationService(ReconciliationRepository repository,
                                            PaymentFactsClient paymentFactsClient,
                                            RefundFactsClient refundFactsClient,
                                            IdempotencyRegistry idempotencyRegistry,
                                            ChannelStatementLoader channelStatementLoader) {
        this.repository = repository;
        this.paymentFactsClient = paymentFactsClient;
        this.refundFactsClient = refundFactsClient;
        this.idempotencyRegistry = idempotencyRegistry;
        this.channelStatementLoader = channelStatementLoader;
    }

    public ReconciliationBatch runReconciliation(String period) {
        IdempotencyKey key = IdempotencyKey.of(IDEMPOTENCY_SCOPE, period);
        Optional<String> existing = idempotencyRegistry.find(key);
        if (existing.isPresent()) {
            return requireBatch(Long.valueOf(existing.get()));
        }

        List<PlatformFact> platform = new ArrayList<>(paymentFactsClient.fetchConfirmedFacts());
        platform.addAll(refundFactsClient.fetchConfirmedFacts());
        List<ChannelStatement> statements = channelStatementLoader.load(period);

        ReconciliationMatchingResult result = ReconciliationMatching.match(platform, statements);

        ReconciliationBatch batch = new ReconciliationBatch(period, MOCK_CHANNEL_SOURCE);
        batch.start();
        batch.finish(result.matches(), result.differences());
        repository.save(batch);

        if (!idempotencyRegistry.recordIfAbsent(key, String.valueOf(batch.getId()))) {
            String winnerId = idempotencyRegistry.find(key)
                    .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR, "idempotency race"));
            return requireBatch(Long.valueOf(winnerId));
        }
        return batch;
    }

    public ReconciliationBatch getBatch(Long id) {
        return requireBatch(id);
    }

    public List<Difference> listDifferences(Long batchId) {
        return requireBatch(batchId).getDifferences();
    }

    public Difference resolveDifference(Long batchId, String reference, String resolutionNote) {
        ReconciliationBatch batch = requireBatch(batchId);
        Difference difference = batch.getDifferences().stream()
                .filter(d -> reference.equals(d.getReference()))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "difference not found: " + reference));
        difference.resolve(resolutionNote);
        repository.save(batch);
        return difference;
    }

    public ReconciliationSettlementSummaryResponse settlementSummary(String period) {
        ReconciliationBatch batch = repository.findByPeriod(period)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "reconciliation batch not found for period: " + period));

        List<ReconciliationSettlementFact> facts = batch.getMatches().stream()
                .map(m -> new ReconciliationSettlementFact(m.reference(), m.type(),
                        m.amountMinor(), m.currencyCode()))
                .toList();

        int unresolved = (int) batch.getDifferences().stream()
                .filter(d -> !d.isResolved())
                .count();

        return new ReconciliationSettlementSummaryResponse(batch.getPeriod(), facts, unresolved);
    }

    private ReconciliationBatch requireBatch(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "reconciliation batch not found: " + id));
    }
}
