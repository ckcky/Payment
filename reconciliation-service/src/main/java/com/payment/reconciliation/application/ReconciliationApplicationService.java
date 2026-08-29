package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.api.ReconciliationSettlementFact;
import com.payment.reconciliation.api.ReconciliationSettlementSummaryResponse;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationMatching;
import com.payment.reconciliation.domain.ReconciliationMatchingResult;
import com.payment.reconciliation.domain.ReconciliationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对账编排（US3）：拉取平台 Payment/Refund 已确认事实与渠道账单，逐笔比对，
 * 产出对账批次（匹配 + 差异）。只读平台事实，绝不修改原始 Payment/Refund。
 *
 * <p>幂等键以 {@code reconciliation:run} 作用域登记：同一周期重复执行返回首次批次，
 * 不重复比对该周期。事实读取在<b>建批之前</b>完成（ADR-0021）：任一侧读取失败 MUST 直接上抛，
 * 绝不落半成品批次，该周期因此可安全重跑。</p>
 */
@Service
public class ReconciliationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationApplicationService.class);
    private static final String MOCK_CHANNEL_SOURCE = "mock-channel";

    private final ReconciliationRepository repository;
    private final PaymentFactsClient paymentFactsClient;
    private final RefundFactsClient refundFactsClient;
    private final ChannelStatementLoader channelStatementLoader;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public ReconciliationApplicationService(ReconciliationRepository repository,
                                            PaymentFactsClient paymentFactsClient,
                                            RefundFactsClient refundFactsClient,
                                            ChannelStatementLoader channelStatementLoader,
                                            BusinessMetrics metrics,
                                            StructuredAuditLogger auditLogger) {
        this.repository = repository;
        this.paymentFactsClient = paymentFactsClient;
        this.refundFactsClient = refundFactsClient;
        this.channelStatementLoader = channelStatementLoader;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
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

        // 事实读取在建批之前（ADR-0021：失败不入批，可安全重跑）。
        List<PlatformFact> platform = new ArrayList<>();
        platform.addAll(fetchWithMetric(paymentFactsClient::fetchConfirmedFacts, "payment", period));
        platform.addAll(fetchWithMetric(refundFactsClient::fetchConfirmedFacts, "refund", period));

        ChannelStatementLoadResult stmtResult = channelStatementLoader.load(period);
        List<ChannelStatement> statements = stmtResult.statements();

        ReconciliationMatchingResult result = ReconciliationMatching.match(platform, statements);

        ReconciliationBatch batch = new ReconciliationBatch(period, MOCK_CHANNEL_SOURCE);
        batch.start();
        batch.finish(result.matches(), result.differences());
        batch.setStatementSource(stmtResult.source());
        batch = insertNew(batch);

        metrics.counter("reconciliation.run", 1, "module", "reconciliation");
        for (Difference difference : batch.getDifferences()) {
            metrics.counter("reconciliation.difference", 1, "module", "reconciliation",
                    "type", difference.getType().name());
        }
        return batch;
    }

    /** 带失败指标与结构化日志的事实读取封装（ADR-0021）；失败上抛，不落半成品批次。 */
    private List<PlatformFact> fetchWithMetric(FactsSupplier supplier, String target, String period) {
        try {
            List<PlatformFact> facts = supplier.get();
            return facts == null ? List.of() : facts;
        } catch (RuntimeException ex) {
            metrics.counter("reconciliation.fact_read_failed", 1, "module", "reconciliation", "target", target);
            log.warn("reconciliation fact read failed: period={} target={} : {}", period, target, ex.getMessage());
            throw ex;
        }
    }

    @FunctionalInterface
    private interface FactsSupplier {
        List<PlatformFact> get();
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

    /**
     * 处理一条差异（ADR-0019）：登记处理依据（MUST 非空）+ 操作人 + 时间，并在首个差异后
     * 将批次推进至 PROCESSING（后续差异幂等）。已处理差异再次处理为幂等刷新。
     */
    @Transactional
    public Difference resolveDifference(Long batchId, String reference, String resolutionNote,
                                        String resolvedBy, String resolvedAt) {
        ReconciliationBatch batch = requireBatch(batchId);
        Difference difference = batch.getDifferences().stream()
                .filter(d -> reference.equals(d.getReference()))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "difference not found: " + reference));
        String at = (resolvedAt == null || resolvedAt.isBlank()) ? Instant.now().toString() : resolvedAt;
        String actor = (resolvedBy == null || resolvedBy.isBlank()) ? "system" : resolvedBy;
        difference.resolve(resolutionNote, actor, at);
        batch.beginProcessing();
        repository.save(batch);
        metrics.counter("reconciliation.difference_resolved", 1, "module", "reconciliation");
        auditLogger.audit("reconciliation.difference_resolved", batch.getPeriod(), 0L, "CNY",
                "HAS_DIFFERENCE", batch.getStatus().name(), "reconciliation", String.valueOf(batch.getId()));
        return difference;
    }

    /**
     * 关闭对账批次（ADR-0019）：显式收口，门禁「尚有未处理差异 ⇒ 拒绝」。CLOSED 为只读终态。
     * 「收口」是审计动作，由资金运营/运维调用，不自动触发。
     */
    @Transactional
    public ReconciliationBatch closeBatch(Long batchId, String operator) {
        ReconciliationBatch batch = requireBatch(batchId);
        String at = Instant.now().toString();
        batch.close(operator, at);
        repository.save(batch);
        metrics.counter("reconciliation.batch_closed", 1, "module", "reconciliation");
        auditLogger.audit("reconciliation.batch_closed", batch.getPeriod(), 0L, "CNY",
                "PROCESSING", "CLOSED", "reconciliation", String.valueOf(batch.getId()));
        return batch;
    }

    public ReconciliationSettlementSummaryResponse settlementSummary(String period) {
        ReconciliationBatch batch = repository.findByPeriod(period)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "reconciliation batch not found for period: " + period));

        List<ReconciliationSettlementFact> facts = batch.getMatches().stream()
                .map(m -> new ReconciliationSettlementFact(m.reference(), m.type(),
                        m.amountMinor(), m.currencyCode()))
                .toList();

        long unresolved = batch.unresolvedDifferenceCount();

        return new ReconciliationSettlementSummaryResponse(batch.getPeriod(), facts, (int) unresolved);
    }

    private ReconciliationBatch requireBatch(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "reconciliation batch not found: " + id));
    }
}
