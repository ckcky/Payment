package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.reconciliation.api.ReconciliationSettlementFact;
import com.payment.reconciliation.api.ReconciliationSettlementSummaryResponse;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationMatching;
import com.payment.reconciliation.domain.ReconciliationMatchingResult;
import com.payment.reconciliation.domain.ReconciliationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final String MOCK_CHANNEL_SOURCE = "mock-channel";

    private final ReconciliationRepository repository;
    private final PaymentFactsClient paymentFactsClient;
    private final RefundFactsClient refundFactsClient;
    private final ChannelStatementLoader channelStatementLoader;
    private final BusinessMetrics metrics;

    public ReconciliationApplicationService(ReconciliationRepository repository,
                                            PaymentFactsClient paymentFactsClient,
                                            RefundFactsClient refundFactsClient,
                                            ChannelStatementLoader channelStatementLoader,
                                            BusinessMetrics metrics) {
        this.repository = repository;
        this.paymentFactsClient = paymentFactsClient;
        this.refundFactsClient = refundFactsClient;
        this.channelStatementLoader = channelStatementLoader;
        this.metrics = metrics;
    }

    /**
     * 对账执行：拉取平台事实与渠道账单逐笔比对，产出对账批次。
     *
     * <p>幂等以周期唯一约束 {@code uk_reconciliation_batches_period} 兜底（非进程内内存登记）：
     * 先按周期回查，未命中则比对并插入；并发/重启后的重复插入撞唯一约束，捕获后回查返回首次批次。</p>
     */
    @Transactional
    public ReconciliationBatch runReconciliation(String period) {
        Optional<ReconciliationBatch> existing = repository.findByPeriod(period);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<PlatformFact> platform = new ArrayList<>(paymentFactsClient.fetchConfirmedFacts());
        platform.addAll(refundFactsClient.fetchConfirmedFacts());
        List<ChannelStatement> statements = channelStatementLoader.load(period);

        ReconciliationMatchingResult result = ReconciliationMatching.match(platform, statements);

        ReconciliationBatch batch = new ReconciliationBatch(period, MOCK_CHANNEL_SOURCE);
        batch.start();
        batch.finish(result.matches(), result.differences());
        batch = insertNew(batch);

        metrics.counter("reconciliation.run", 1, "module", "reconciliation");
        for (Difference difference : batch.getDifferences()) {
            metrics.counter("reconciliation.difference", 1, "module", "reconciliation",
                    "type", difference.getType().name());
        }
        return batch;
    }

    /** 插入新批次；并发/重启后撞周期唯一约束时，回查并返回首次批次（不重复比对）。 */
    private ReconciliationBatch insertNew(ReconciliationBatch batch) {
        try {
            return repository.save(batch);
        } catch (DuplicateKeyException e) {
            return repository.findByPeriod(batch.getPeriod())
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "reconciliation batch duplicate: " + batch.getPeriod()));
        }
    }

    public ReconciliationBatch getBatch(Long id) {
        return requireBatch(id);
    }

    public List<Difference> listDifferences(Long batchId) {
        return requireBatch(batchId).getDifferences();
    }

    @Transactional
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
