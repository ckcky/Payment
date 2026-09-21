package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.api.ReconciliationExcludedFact;
import com.payment.reconciliation.api.ReconciliationSettlementFact;
import com.payment.reconciliation.api.ReconciliationSettlementSummaryResponse;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.Difference;
import com.payment.reconciliation.domain.DifferenceStatus;
import com.payment.reconciliation.domain.PlatformFact;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationDifference;
import com.payment.reconciliation.domain.ReconciliationMatching;
import com.payment.reconciliation.domain.ReconciliationMatchingResult;
import com.payment.reconciliation.domain.ReconciliationRepository;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.statement.TypedStatementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对账编排（US3 + spec 032 G1/G4）：以导入的标准化账单为外部资金事实来源，拉取平台
 * Payment/Refund 已确认事实逐笔比对，产出对账批次（匹配 + 差异台账）。只读平台事实，
 * 绝不修改原始 Payment/Refund。
 *
 * <p>幂等（spec 032 §8.3）：执行键 = {@code (channelCode, period, importId)}——同一导入重跑
 * 返回首次批次，不重复比对；同周期更正账单 = 新导入 = 新批次并存（CLOSED 批次可重对）。
 * 事实读取在<b>建批之前</b>完成（ADR-0021）：任一侧读取失败直接上抛，绝不落半成品批次；
 * 无可用 NORMALIZED 导入 ⇒ 400 {@code STATEMENT_UNAVAILABLE}（sample.csv 回退已退役）。</p>
 */
@Service
public class ReconciliationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationApplicationService.class);

    private final ReconciliationRepository repository;
    private final PaymentFactsClient paymentFactsClient;
    private final RefundFactsClient refundFactsClient;
    private final StatementImportRepository importRepository;
    private final AutoDispositionService autoDispositionService;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public ReconciliationApplicationService(ReconciliationRepository repository,
                                            PaymentFactsClient paymentFactsClient,
                                            RefundFactsClient refundFactsClient,
                                            StatementImportRepository importRepository,
                                            AutoDispositionService autoDispositionService,
                                            BusinessMetrics metrics,
                                            StructuredAuditLogger auditLogger) {
        this.repository = repository;
        this.paymentFactsClient = paymentFactsClient;
        this.refundFactsClient = refundFactsClient;
        this.importRepository = importRepository;
        this.autoDispositionService = autoDispositionService;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 对账执行（spec §10.1 run）：缺省取该渠道该周期最新 NORMALIZED 导入；{@code importNo}
     * 显式指定时以该导入为准（更正账单重对入口）。
     */
    @Transactional
    public ReconciliationBatch runReconciliation(String period, String channelCode, String importNo) {
        String channel = channelCode == null || channelCode.isBlank()
                ? ReconciliationBatch.LEGACY_CHANNEL : channelCode;

        StatementImport imprt = resolveImport(period, channel, importNo);

        // 幂等键回查：同一导入重跑返回首次批次（不重复比对、不重复产差异）。
        Optional<ReconciliationBatch> existing = repository.findByPeriodAndImport(period, channel, imprt.getId());
        if (existing.isPresent()) {
            log.info("reconciliation run replay (idempotent): period={} channel={} importNo={} batchNo={}",
                    period, channel, imprt.getImportNo(), existing.get().getBatchNo());
            return existing.get();
        }

        // 事实读取在建批之前（ADR-0021：失败不入批，可安全重跑）。
        List<PlatformFact> platform = fetchFacts(period);

        List<StatementLine> lines = importRepository.findLines(imprt.getId());
        List<ReconciliationMatching.StatementLineView> views = lines.stream()
                .<ReconciliationMatching.StatementLineView>map(TypedStatementLine::new).toList();
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(platform, views);

        ReconciliationBatch batch = new ReconciliationBatch(period, channel, channel, imprt.getId());
        batch.start();
        batch = insertNew(batch);
        batch.setStatementSource(new ChannelStatementSource("IMPORT", imprt.getImportNo(), lines.size(), false));

        // 差异台账（differences_json 停写）：RD 单号 + uk_diff_identity 幂等吸收（同周期同类差异
        // 已存在 ⇒ 采用既有行及其处置状态，重跑不产生第二条同类差异）。
        List<Difference> ledgerBacked = new ArrayList<>();
        for (Difference difference : result.differences()) {
            ReconciliationDifference probe = ReconciliationDifference.of(batch.getId(), imprt.getId(), period,
                    difference.getMerchantId(), channel, difference.getType(), difference.getReferenceType(),
                    difference.getReference(), difference.getPlatformAmountMinor(),
                    difference.getChannelAmountMinor(), difference.getFeeAmountMinor(), null);
            ReconciliationDifference saved = repository.insertDifference(probe);
            ledgerBacked.add(withLedgerState(difference, saved));
        }
        batch.finish(result.matches(), ledgerBacked);
        batch = repository.save(batch);

        metrics.counter("reconciliation.run", 1, "module", "reconciliation");
        Map<String, Long> kindCounts = new HashMap<>();
        for (Difference difference : batch.getDifferences()) {
            kindCounts.merge(difference.getType().name(), 1L, Long::sum);
            metrics.counter("reconciliation.difference", 1, "module", "reconciliation",
                    "kind", difference.getType().name(), "severity", difference.getType().severity());
        }
        long unmatched = batch.unmatchedAmountMinor();
        if (unmatched > 0) {
            metrics.counter("reconciliation.unmatched_amount_minor", unmatched,
                    "module", "reconciliation", "currency", "CNY");
        }
        // 批次差异金额口径（spec 006 T040 沿用 + plan §2.3）：双侧取差额，单侧缺失取该侧。
        long differenceAmount = batch.differenceAmountMinor();
        if (differenceAmount > 0) {
            metrics.counter("reconciliation.difference_amount_minor", differenceAmount,
                    "module", "reconciliation", "period", period);
        }
        auditLogger.audit("reconciliation.run", imprt.getImportNo(), differenceAmount, "CNY", "RECONCILING",
                batch.getStatus().name(), "reconciliation", batch.getBatchNo());
        log.info("reconciliation run completed: period={} channel={} importNo={} batchNo={} matches={} differences={}",
                period, channel, imprt.getImportNo(), batch.getBatchNo(),
                batch.getMatches().size(), batch.getDifferences().size());

        // G5 自动处置（H-032-6，保守开关默认关）：对满足策略门的 PENDING 渠道长款自动挂账。
        autoDispositionService.disposeBatch(batch.getId());
        return batch;
    }

    /** 兼容入口（032 前形态）：缺省 MOCK 渠道、最新 NORMALIZED 导入。 */
    @Transactional
    public ReconciliationBatch runReconciliation(String period) {
        return runReconciliation(period, null, null);
    }

    /** 无可用 NORMALIZED 导入 ⇒ 400 STATEMENT_UNAVAILABLE + 指标（spec §11 #1，fail fast）。 */
    private StatementImport resolveImport(String period, String channel, String importNo) {
        if (importNo != null && !importNo.isBlank()) {
            StatementImport byNo = importRepository.findByNo(importNo)
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                            "statement import not found: " + importNo));
            if (!byNo.normalized()) {
                throw BizException.of(ErrorCodes.STATEMENT_UNAVAILABLE,
                        "statement import " + importNo + " is " + byNo.getStatus() + " (NORMALIZED required)");
            }
            return byNo;
        }
        return importRepository.findLatestNormalized(period, channel)
                .orElseThrow(() -> {
                    metrics.counter("reconciliation.statement_unavailable", 1,
                            "channel", channel, "period", period);
                    log.warn("statement unavailable: channel={} period={} (upload a statement before running)",
                            channel, period);
                    return BizException.of(ErrorCodes.STATEMENT_UNAVAILABLE,
                            "no normalized statement import for channel " + channel + " period " + period
                                    + "; upload a statement first");
                });
    }

    /** 台账行回写批内视图：采用既有行的 diffNo 与处置状态（uk 吸收语义）。 */
    private Difference withLedgerState(Difference source, ReconciliationDifference saved) {
        String resolutionStatus = saved.getStatus() == DifferenceStatus.RESOLVED ? Difference.RESOLVED
                : saved.getStatus().name();
        return new Difference(source.getReference(), source.getType(), source.getPlatformAmountMinor(),
                source.getChannelAmountMinor(), source.getPlatformStatus(), source.getChannelStatus(),
                resolutionStatus, null, null, null, saved.getDiffNo(), source.getMerchantId(),
                source.getReferenceType(), source.getFeeAmountMinor());
    }

    /** 带失败指标与结构化日志的事实读取封装（ADR-0021）；失败上抛，不落半成品批次。 */
    private List<PlatformFact> fetchFacts(String period) {
        List<PlatformFact> platform = new ArrayList<>();
        platform.addAll(fetchWithMetric(() -> paymentFactsClient.fetchConfirmedFacts(period), "payment", period));
        platform.addAll(fetchWithMetric(() -> refundFactsClient.fetchConfirmedFacts(period), "refund", period));
        return platform;
    }

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

    /** 插入新批次；并发/重启后撞身份唯一约束时，回查并返回首次批次（不重复比对）。 */
    private ReconciliationBatch insertNew(ReconciliationBatch batch) {
        try {
            return repository.save(batch);
        } catch (DuplicateKeyException e) {
            return repository.findByPeriodAndImport(batch.getPeriod(), batch.getChannelCode(), batch.getImportId())
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "reconciliation batch duplicate: " + batch.getPeriod() + "/" + batch.getChannelCode()));
        }
    }

    public ReconciliationBatch getBatch(Long id) {
        return requireBatch(id);
    }

    public List<Difference> listDifferences(Long batchId) {
        return requireBatch(batchId).getDifferences();
    }

    /** 差异分页查询（spec §10.1 GET differences；拆表后能力）。 */
    public ReconciliationRepository.DifferencePage searchDifferences(String period, String status,
                                                                     String merchantId, String kind,
                                                                     int page, int size) {
        return repository.findDifferences(period, status, merchantId, kind, Math.max(0, page),
                Math.min(Math.max(1, size), 200));
    }

    public Optional<ReconciliationDifference> getDifference(String diffNo) {
        return repository.findDifferenceByNo(diffNo);
    }

    /**
     * 处理一条差异（ADR-0019，legacy 入口）：登记处理依据（MUST 非空）+ 操作人 + 时间，并在首个差异后
     * 将批次推进至 PROCESSING（后续差异幂等）。已处理差异再次处理为幂等刷新。
     * 032 起携带 diffNo 的差异同步回写台账（differences_json 停写）。
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
        if (difference.getDiffNo() != null) {
            repository.findDifferenceByNo(difference.getDiffNo()).ifPresent(row -> {
                row.resolve(resolutionNote, actor, at);
                repository.updateDifference(row);
            });
        }
        batch.beginProcessing();
        repository.save(batch);
        metrics.counter("reconciliation.difference_resolved", 1, "module", "reconciliation");
        auditLogger.audit("reconciliation.difference_resolved", batch.getPeriod(), 0L, "CNY",
                "HAS_DIFFERENCE", batch.getStatus().name(), "reconciliation", String.valueOf(batch.getId()));
        return difference;
    }

    /**
     * 人工收口（spec §10.1，主入口）：按 RD 单号 resolve 台账行（备注必填），并同步批次内视图
     * （关批门禁口径）。已 RESOLVED 差异再次收口为幂等空操作。
     */
    @Transactional
    public ReconciliationDifference resolveDifferenceByNo(String diffNo, String resolutionNote,
                                                          String resolvedBy, String resolvedAt) {
        ReconciliationDifference row = repository.findDifferenceByNo(diffNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "difference not found: " + diffNo));
        String at = (resolvedAt == null || resolvedAt.isBlank()) ? Instant.now().toString() : resolvedAt;
        String actor = (resolvedBy == null || resolvedBy.isBlank()) ? "system" : resolvedBy;
        row.resolve(resolutionNote, actor, at);
        repository.updateDifference(row);

        ReconciliationBatch batch = repository.findById(row.getBatchId()).orElse(null);
        if (batch != null) {
            for (Difference d : batch.getDifferences()) {
                if (diffNo.equals(d.getDiffNo()) && !d.isResolved()) {
                    d.resolve(resolutionNote, actor, at);
                }
            }
            // 台账行先于批次视图更新，故不以 changed 判定：本单号已收口即推进
            // HAS_DIFFERENCE → PROCESSING（beginProcessing 对 PROCESSING 幂等，ADR-0019）。
            // 否则重放/末笔收口时 changed 恒假，批次停在 HAS_DIFFERENCE，关批 409。
            String status = batch.getStatus().name();
            if (status.equals("HAS_DIFFERENCE") || status.equals("PROCESSING")) {
                batch.beginProcessing();
                repository.save(batch);
            }
        }
        metrics.counter("reconciliation.difference_resolved", 1, "module", "reconciliation");
        auditLogger.audit("reconciliation.difference_resolved", row.getPeriod(), 0L, row.getCurrency(),
                row.getStatus().name(), "RESOLVED", "reconciliation", diffNo);
        return row;
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

    /**
     * 结算汇总（032/G4，C-13 收口）：口径 = 「全部已确认事实 − 未收口差异净影响」。
     *
     * <p>{@code facts} 为该周期全部已确认事实（按期间拉取，不再静默剔除未匹配事实）；
     * {@code excludedFacts} 为未收口差异（PENDING/SUSPENDED/ADJUSTING/ADJUSTED）对结算口径的
     * 显式扣减（含原因）：PLATFORM_ONLY/STATUS_MISMATCH = 事实全额（渠道未证实），
     * AMOUNT_MISMATCH = |平台 − 渠道|，其余类型不影响商户结算口径。事实仍保留在
     * {@code facts} 中（TC-032-11：挂账后放行仍含事实、只扣净影响）。批次取「最新批」语义
     * （同周期可并存多份导入批次）。</p>
     */
    public ReconciliationSettlementSummaryResponse settlementSummary(String period) {
        ReconciliationBatch batch = repository.findByPeriod(period)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "reconciliation batch not found for period: " + period));

        // 全部已确认事实：按期间向上游拉取（G4 口径；失败直接上抛，绝不产出「空事实即无欠账」）。
        List<PlatformFact> platform = fetchFacts(period);
        List<ReconciliationSettlementFact> facts = platform.stream()
                .map(f -> new ReconciliationSettlementFact(f.reference(), f.type(),
                        f.amountMinor(), f.currencyCode(), f.merchantId()))
                .toList();

        List<ReconciliationExcludedFact> excludedFacts = excludedFactsOf(batch, platform);

        long unresolved = batch.unresolvedDifferenceCount();

        return new ReconciliationSettlementSummaryResponse(batch.getPeriod(), facts, excludedFacts,
                (int) unresolved);
    }

    /**
     * 未收口差异 → 结算扣减项（plan §2.3 净影响定义）。
     * 未收口集合 = PENDING/SUSPENDED/ADJUSTING/ADJUSTED（含 ADJUSTED：调账已完成但事实仍未证实，
     * 放行会同结算双付）；已 RESOLVED 的差异不再扣减。扣减侧（PAYMENT/REFUND）按该引用的平台事实类型确定，
     * 引用无对应平台事实（如 CHANNEL_ONLY）不产生扣减。
     */
    private List<ReconciliationExcludedFact> excludedFactsOf(ReconciliationBatch batch, List<PlatformFact> platform) {
        Map<String, String> typeByReference = new HashMap<>();
        for (PlatformFact f : platform) {
            typeByReference.putIfAbsent(f.reference(), f.type());
        }
        List<ReconciliationExcludedFact> excluded = new ArrayList<>();
        for (Difference d : batch.getDifferences()) {
            if (d.isResolved()) {
                continue;
            }
            String side = typeByReference.get(d.getReference());
            if (side == null) {
                continue;
            }
            Long impact = switch (d.getType()) {
                case PLATFORM_ONLY, STATUS_MISMATCH -> d.getPlatformAmountMinor();
                case AMOUNT_MISMATCH -> Math.abs(
                        (d.getPlatformAmountMinor() == null ? 0L : d.getPlatformAmountMinor())
                                - (d.getChannelAmountMinor() == null ? 0L : d.getChannelAmountMinor()));
                // CHANNEL_ONLY / FEE_MISMATCH / UNKNOWN_MAPPING / DUPLICATE_CHANNEL 不进入商户结算口径
                default -> null;
            };
            if (impact != null && impact != 0) {
                excluded.add(new ReconciliationExcludedFact(d.getReference(), side, impact, d.getType().name()));
            }
        }
        return List.copyOf(excluded);
    }

    private ReconciliationBatch requireBatch(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "reconciliation batch not found: " + id));
    }
}
