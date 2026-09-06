package com.payment.reconciliation.audit.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditAdjustmentKind;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditBatchStatus;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.audit.domain.AuditDifferenceStatus;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.audit.domain.AuditScope;
import com.payment.reconciliation.audit.domain.AuditSeverity;
import com.payment.reconciliation.audit.domain.SuspensePolicy;
import com.payment.reconciliation.domain.ReconciliationBatch;
import com.payment.reconciliation.domain.ReconciliationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审计批次编排（spec 017）：四核对执行、挂账 / 调账 / recheck / 关批门禁 / 结算门禁。
 *
 * <p>纪律（宪法 + plan §12）：核对只读业务域；写仅限 audit_* 表与 ledger 的 ADJUSTMENT 分录；
 * 处置必须人工发起（operator + reason），绝不自动修钱（NFR-005）；任一数据源不可达时批次失败
 * 并明确原因（NFR-008）。幂等：(period, scope) 唯一约束兜底，重复触发回查首批次（FR-003）。</p>
 */
@Service
public class AuditApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuditApplicationService.class);

    private final AuditRepository auditRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final AuditFactsGateway factsGateway;
    private final AuditLedgerGateway ledgerGateway;
    private final CertificateAuditor certificateAuditor;
    private final LedgerAuditor ledgerAuditor;
    private final RealAuditor realAuditor;
    private final ReportAuditor reportAuditor;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;
    private final boolean writeOffEnabled;
    private final boolean enforceDoubleCheck;

    public AuditApplicationService(AuditRepository auditRepository,
                                   ReconciliationRepository reconciliationRepository,
                                   AuditFactsGateway factsGateway,
                                   AuditLedgerGateway ledgerGateway,
                                   CertificateAuditor certificateAuditor,
                                   LedgerAuditor ledgerAuditor,
                                   RealAuditor realAuditor,
                                   ReportAuditor reportAuditor,
                                   BusinessMetrics metrics,
                                   StructuredAuditLogger auditLogger,
                                   @Value("${audit.adjust.write-off.enabled:false}") boolean writeOffEnabled,
                                   @Value("${audit.review.enforce-double-check:false}") boolean enforceDoubleCheck) {
        this.auditRepository = auditRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.factsGateway = factsGateway;
        this.ledgerGateway = ledgerGateway;
        this.certificateAuditor = certificateAuditor;
        this.ledgerAuditor = ledgerAuditor;
        this.realAuditor = realAuditor;
        this.reportAuditor = reportAuditor;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.writeOffEnabled = writeOffEnabled;
        this.enforceDoubleCheck = enforceDoubleCheck;
    }

    // ---------------------------------------------------------------- 批次执行

    /**
     * 触发核对批次（FR-003 幂等）：事实读取在建批之前完成（失败不入批，可安全重跑）。
     */
    @Transactional
    public AuditBatch runBatch(String period, String scopeStr, String triggeredBy) {
        AuditScope scope = AuditScope.valueOf(scopeStr);
        Optional<AuditBatch> existing = auditRepository.findBatchByPeriodAndScope(period, scope);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 事实读取建批之前（NFR-008：任一数据源不可达 → 上抛，绝不静默产出「无差异」）
        List<CertificateFact> facts = factsGateway.confirmedFacts(period);
        List<LedgerPostingView> postings = factsGateway.ledgerPostings();
        LedgerBalance balance = factsGateway.ledgerBalance();
        List<SettlementBatchFact> settlementFacts = factsGateway.settlementFacts(period);
        long unclosedSuspense = auditRepository.sumUnclosedSuspendedAmountMinor();

        List<AuditDifference> differences = new ArrayList<>();
        int checkedCount = (int) facts.stream().filter(CertificateFact::confirmed).count();

        if (scope == AuditScope.CERTIFICATE || scope == AuditScope.ALL) {
            differences.addAll(certificateAuditor.audit(facts, postings));
        }
        if (scope == AuditScope.LEDGER || scope == AuditScope.ALL) {
            differences.addAll(ledgerAuditor.audit(facts, settlementFacts, postings, balance, unclosedSuspense));
        }
        if (scope == AuditScope.REAL || scope == AuditScope.ALL) {
            differences.addAll(realAuditor.audit(facts, factsGateway.channelStatements(period), postings));
        }
        if (scope == AuditScope.REPORT || scope == AuditScope.ALL) {
            differences.addAll(reportAuditor.audit(reportMatches(period), facts));
        }

        AuditBatch batch = new AuditBatch(period, scope, triggeredBy);
        batch.finish(checkedCount, differences);
        batch = insertNewBatch(batch);
        persistDifferences(batch);
        refreshDispositionSums(batch);
        auditRepository.saveBatch(batch);

        metrics.counter("audit.batch_run", 1, "module", "reconciliation", "scope", scope.name());
        for (AuditDifference difference : batch.getDifferences()) {
            metrics.counter("audit.difference", 1, "module", "reconciliation",
                    "kind", difference.getKind().name(), "severity", difference.getSeverity().name());
        }
        auditLogger.audit("audit.batch_finished", period, 0L, "CNY", "PROCESSING",
                batch.getStatus().name(), "audit", batch.getBatchNo());
        log.info("audit batch finished: batchNo={} period={} scope={} checked={} differences={}",
                batch.getBatchNo(), period, scope, checkedCount, batch.getDifferences().size());
        return batch;
    }

    public AuditBatch getBatch(String batchNo) {
        return requireBatch(batchNo);
    }

    public List<AuditDifference> listDifferences(String batchNo) {
        return requireBatch(batchNo).getDifferences();
    }

    /** 处置台账查询（FR-019）：批次内所有挂账 / 调账流水。 */
    public List<AuditAdjustment> listAdjustments(String batchNo) {
        AuditBatch batch = requireBatch(batchNo);
        return auditRepository.findAdjustmentsByBatch(batch.getId());
    }

    // ---------------------------------------------------------------- 挂账 / 调账

    /**
     * 挂账（FR-014）：差额安置到 SUSPENSE 过渡科目。金额 = 差异剩余未处置金额。
     * 生成借贷平衡分录 + 台账留痕；业务单据状态一律不变（FR-020）。
     */
    @Transactional
    public AuditAdjustment suspend(String batchNo, Long differenceId, String operator, String reason) {
        AuditBatch batch = requireBatch(batchNo);
        if (batch.getStatus() == AuditBatchStatus.CLOSED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "batch closed: " + batchNo);
        }
        AuditDifference difference = requireDifference(batch, differenceId);
        long amount = difference.remainingAmountMinor();
        if (amount <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "difference fully disposed: " + differenceId);
        }
        boolean underRecorded = SuspensePolicy.isUnderRecorded(difference.getKind(),
                difference.getExpectedAmountMinor(), difference.getActualAmountMinor());

        String adjustNo = BusinessNos.of(BusinessNoType.AUDIT_ADJUSTMENT);
        AdjustmentPolicy.PostingPlan plan = AdjustmentPolicy.buildPlan(adjustNo, AuditAdjustmentKind.SUSPEND,
                underRecorded, amount, null, null);
        AuditLedgerGateway.PostingResult result = ledgerGateway.postAdjustment(
                plan.idempotencyKey(), adjustNo, difference.getCurrency(), plan.entries());

        AuditAdjustment adjustment = new AuditAdjustment(null, adjustNo, batch.getId(), difference.getId(),
                AuditAdjustmentKind.SUSPEND,
                underRecorded ? SuspensePolicy.CUSTOMER_CASH : SuspensePolicy.SUSPENSE,
                underRecorded ? SuspensePolicy.SUSPENSE : SuspensePolicy.CUSTOMER_CASH,
                amount, difference.getCurrency(), result.postingNo(), "POSTED", operator, null, reason);
        adjustment = auditRepository.insertAdjustment(adjustment);

        difference.suspend(amount);
        auditRepository.saveDifference(batch.getId(), difference);
        refreshDispositionSums(batch);
        auditRepository.saveBatch(batch);

        metrics.counter("audit.suspend", 1, "module", "reconciliation");
        auditLogger.audit("audit.difference_suspended", batch.getPeriod(), amount, difference.getCurrency(),
                difference.getKind().name(), "SUSPENDED", operator, adjustNo);
        log.info("audit suspend: adjustNo={} difference={} amount={} posting={}", adjustNo,
                difference.getSourceType() + "/" + difference.getSourceId(), amount, result.postingNo());
        return adjustment;
    }

    /**
     * 调账（FR-015 / FR-016）：五类 kind 走标准记账通道生成 ADJUSTMENT 分录；
     * 处置后自动 recheck（plan §7.2 规则 8），通过置 VERIFIED。
     */
    @Transactional
    public AuditAdjustment adjust(String batchNo, Long differenceId, String kindStr, long amountMinor,
                                  String targetAccountCode, String operator, String reviewer, String reason) {
        AuditBatch batch = requireBatch(batchNo);
        if (batch.getStatus() == AuditBatchStatus.CLOSED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "batch closed: " + batchNo);
        }
        AuditDifference difference = requireDifference(batch, differenceId);
        AuditAdjustmentKind kind = AuditAdjustmentKind.valueOf(kindStr);
        AdjustmentPolicy.validate(kind, amountMinor, operator, reviewer, reason,
                writeOffEnabled, enforceDoubleCheck);
        if (AdjustmentPolicy.needsReview(kind, amountMinor, operator, reviewer)) {
            // 软约束（§11 ⑥）：WARN 留痕不阻断
            metrics.counter("audit.adjustment.single_operator", 1, "module", "reconciliation");
            log.warn("audit adjustment without double-check: adjustNo pending kind={} amount={} operator={} reviewer={}",
                    kind, amountMinor, operator, reviewer);
        }

        long amount;
        if (kind == AuditAdjustmentKind.TRANSFER) {
            amount = Math.min(amountMinor, difference.getSuspendedAmountMinor() - difference.getTransferredOutMinor());
        } else {
            amount = Math.min(amountMinor, difference.remainingAmountMinor());
        }
        if (amount != amountMinor) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "ADJUST_AMOUNT_EXCEEDED");
        }

        String adjustNo = BusinessNos.of(BusinessNoType.AUDIT_ADJUSTMENT);
        boolean underRecorded = SuspensePolicy.isUnderRecorded(difference.getKind(),
                difference.getExpectedAmountMinor(), difference.getActualAmountMinor());
        AdjustmentPolicy.PostingPlan plan = AdjustmentPolicy.buildPlan(adjustNo, kind, underRecorded, amount,
                targetAccountCode, originalEntries(difference));
        AuditLedgerGateway.PostingResult result = ledgerGateway.postAdjustment(
                plan.idempotencyKey(), adjustNo, difference.getCurrency(), plan.entries());

        String debitAccount = plan.entries().stream()
                .filter(e -> "DEBIT".equals(e.direction())).findFirst()
                .map(e -> accountCode(e.accountId())).orElse("UNKNOWN");
        String creditAccount = plan.entries().stream()
                .filter(e -> "CREDIT".equals(e.direction())).findFirst()
                .map(e -> accountCode(e.accountId())).orElse("UNKNOWN");
        AuditAdjustment adjustment = new AuditAdjustment(null, adjustNo, batch.getId(), difference.getId(),
                kind, debitAccount, creditAccount,
                amount, difference.getCurrency(), result.postingNo(), "POSTED", operator, reviewer, reason);
        adjustment = auditRepository.insertAdjustment(adjustment);

        if (kind == AuditAdjustmentKind.TRANSFER) {
            difference.transferOut(amount);
        } else {
            difference.applyAdjustment(amount);
        }
        auditRepository.saveDifference(batch.getId(), difference);
        refreshDispositionSums(batch);
        auditRepository.saveBatch(batch);

        metrics.counter("audit.adjust", 1, "module", "reconciliation", "kind", kind.name());
        auditLogger.audit("audit.difference_adjusted", batch.getPeriod(), amount, difference.getCurrency(),
                difference.getKind().name(), "ADJUSTED", operator, adjustNo);

        // 处置后自动 recheck（FR-017）：通过置 VERIFIED，否则维持未收口继续暴露
        boolean verified = recheckDifference(batch, difference);
        log.info("audit adjust: adjustNo={} kind={} amount={} posting={} verified={}",
                adjustNo, kind, amount, result.postingNo(), verified);
        return adjustment;
    }

    /**
     * recheck（FR-017）：对该差异重跑比对——通过置 VERIFIED，未通过退回 SUSPENDED 继续暴露。
     */
    @Transactional
    public AuditBatch recheck(String batchNo) {
        AuditBatch batch = requireBatch(batchNo);
        if (batch.getStatus() == AuditBatchStatus.CLOSED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "batch closed: " + batchNo);
        }
        batch.beginRechecking();
        for (AuditDifference difference : batch.getDifferences()) {
            if (difference.getStatus() == AuditDifferenceStatus.PENDING) {
                continue; // 未处置的差异保持暴露
            }
            boolean verified = recheckDifference(batch, difference);
            if (!verified) {
                difference.rejectRecheck();
            }
            auditRepository.saveDifference(batch.getId(), difference);
        }
        if (batch.hasUnclosedDifferences()) {
            batch.setStatus(AuditBatchStatus.HAS_DIFFERENCE);
        } else {
            batch.setStatus(AuditBatchStatus.BALANCED);
        }
        auditRepository.saveBatch(batch);
        metrics.counter("audit.recheck", 1, "module", "reconciliation");
        return batch;
    }

    /** 单差异 recheck：账证类按资金科目净影响判定，其余重跑对应审计器。 */
    private boolean recheckDifference(AuditBatch batch, AuditDifference difference) {
        if (difference.getKind().isCertificateKind()) {
            List<CertificateFact> facts = factsGateway.confirmedFacts(batch.getPeriod());
            CertificateFact fact = facts.stream()
                    .filter(f -> f.sourceType().equals(difference.getSourceType())
                            && f.sourceId().equals(difference.getSourceId()))
                    .findFirst().orElse(null);
            List<LedgerPostingView> postings = factsGateway.ledgerPostings();
            List<LedgerPostingView> linked = linkedAdjustmentPostings(difference, postings);
            if (fact == null || !fact.confirmed()) {
                // 孤儿分录：业务侧本无事实，处置（红冲）后净影响应为 0
                if (difference.getKind() == AuditDifferenceKind.ORPHAN_POSTING) {
                    boolean zeroed = certificateAuditor.sourceCustomerCashNet(
                            difference.getSourceType(), difference.getSourceId(), postings, linked) == 0L;
                    if (zeroed) {
                        difference.verify();
                    }
                    return zeroed;
                }
                return false;
            }
            boolean ok = certificateAuditor.sourceBalanced(fact, postings, linked);
            if (ok) {
                difference.verify();
            }
            return ok;
        }
        // 非账证类：重跑对应审计器，看该 kind+source 是否仍出现
        List<AuditDifference> fresh = recomputeForKind(batch.getPeriod(), difference.getKind());
        boolean stillPresent = fresh.stream().anyMatch(d ->
                d.getKind() == difference.getKind()
                        && d.getSourceType().equals(difference.getSourceType())
                        && d.getSourceId().equals(difference.getSourceId()));
        if (!stillPresent) {
            difference.verify();
        }
        return !stillPresent;
    }

    /** 与差异关联的 ADJUSTMENT posting（经处置台账 difference_id 关联）。 */
    private List<LedgerPostingView> linkedAdjustmentPostings(AuditDifference difference,
                                                             List<LedgerPostingView> postings) {
        List<String> adjustNos = auditRepository.findAdjustmentsByDifference(difference.getId()).stream()
                .map(AuditAdjustment::getAdjustNo)
                .toList();
        return postings.stream()
                .filter(p -> "ADJUSTMENT".equals(p.sourceType()) && adjustNos.contains(p.sourceId()))
                .toList();
    }

    private List<AuditDifference> recomputeForKind(String period, com.payment.reconciliation.audit.domain.AuditDifferenceKind kind) {
        List<CertificateFact> facts = factsGateway.confirmedFacts(period);
        List<LedgerPostingView> postings = factsGateway.ledgerPostings();
        if (kind.isCertificateKind()) {
            return certificateAuditor.audit(facts, postings);
        }
        return switch (kind) {
            case BALANCE_BREAK, ACCOUNT_RECON_BREAK, CROSS_LEDGER_MISMATCH -> ledgerAuditor.audit(facts,
                    factsGateway.settlementFacts(period), postings, factsGateway.ledgerBalance(),
                    auditRepository.sumUnclosedSuspendedAmountMinor());
            case LEDGER_VS_STATEMENT_BREAK -> realAuditor.audit(facts, factsGateway.channelStatements(period), postings);
            case REPORT_MISMATCH -> reportAuditor.audit(reportMatches(period), facts);
            default -> List.of();
        };
    }

    // ---------------------------------------------------------------- 关批 / 门禁

    /**
     * 关批门禁（FR-018）：存在 PENDING / SUSPENDED / ADJUSTED 差异 → 400；全收口 → CLOSED。
     */
    @Transactional
    public AuditBatch close(String batchNo, String operator) {
        AuditBatch batch = requireBatch(batchNo);
        try {
            batch.close(operator);
        } catch (BizException e) {
            metrics.counter("audit.close_blocked", 1, "module", "reconciliation");
            throw e;
        }
        auditRepository.saveBatch(batch);
        metrics.counter("audit.batch_closed", 1, "module", "reconciliation");
        auditLogger.audit("audit.batch_closed", batch.getPeriod(), 0L, "CNY",
                "HAS_DIFFERENCE", "CLOSED", operator, batch.getBatchNo());
        return batch;
    }

    /**
     * 结算门禁（plan §6.1 分级）：BLOCKER 且未挂账拦截；已挂账 / 已调账放行留痕；
     * 借贷不平衡硬拦。无审计批次时仅校验借贷平衡（无证据 ≠ 违规）。
     */
    public SettlementGateResponse settlementGate(String period) {
        LedgerBalance balance = factsGateway.ledgerBalance();
        boolean balanced = balance != null && balance.balanced();
        List<SettlementGateResponse.BlockingDifference> blocking = new ArrayList<>();
        if (!balanced) {
            balance.diffByCurrency().entrySet().stream()
                    .filter(e -> e.getValue() != 0L)
                    .forEach(e -> blocking.add(new SettlementGateResponse.BlockingDifference(
                            "BALANCE_BREAK", "LEDGER", e.getKey(), "BLOCKER", Math.abs(e.getValue()), e.getKey())));
        }
        for (AuditScope scope : List.of(AuditScope.CERTIFICATE, AuditScope.LEDGER, AuditScope.ALL)) {
            Optional<AuditBatch> batch = auditRepository.findBatchByPeriodAndScope(period, scope);
            batch.ifPresent(b -> b.getDifferences().stream()
                    .filter(d -> d.getSeverity() == AuditSeverity.BLOCKER
                            && d.getStatus() == AuditDifferenceStatus.PENDING)
                    .forEach(d -> blocking.add(new SettlementGateResponse.BlockingDifference(
                            d.getKind().name(), d.getSourceType(), d.getSourceId(), d.getSeverity().name(),
                            d.differenceAmountMinor(), d.getCurrency()))));
        }
        String decision = blocking.isEmpty() ? "ALLOW" : "BLOCK";
        metrics.counter("audit.settlement.gate." + decision.toLowerCase(), 1, "module", "reconciliation");
        return new SettlementGateResponse(decision, balanced, List.copyOf(blocking));
    }

    /** SUSPENSE 余额（SC-016：账本实算口径，贷方为正——挂账记贷、转出记借）。 */
    public long suspenseBalanceMinor() {
        return -factsGateway.ledgerPostings().stream()
                .mapToLong(p -> p.signedAmountForAccount(5L))
                .sum();
    }

    /** 试算平衡（SC-018）：任意处置序列之后 Σ(借−贷) 恒为 0。 */
    public LedgerBalance trialBalance() {
        return factsGateway.ledgerBalance();
    }

    // ---------------------------------------------------------------- 内部

    private List<ReportAuditor.MatchView> reportMatches(String period) {
        Optional<ReconciliationBatch> batch = reconciliationRepository.findByPeriod(period);
        if (batch.isEmpty()) {
            return List.of();
        }
        return batch.get().getMatches().stream()
                .map(m -> new ReportAuditor.MatchView(m.reference(), m.type(), m.amountMinor(), m.currencyCode()))
                .toList();
    }

    private List<AdjustmentPolicy.PostingEntry> originalEntries(AuditDifference difference) {
        if (difference.getKind() != AuditDifferenceKind.ORPHAN_POSTING
                && difference.getKind() != AuditDifferenceKind.DUPLICATE_POSTING
                && difference.getKind() != AuditDifferenceKind.AMOUNT_MISMATCH
                && difference.getKind() != AuditDifferenceKind.DIRECTION_MISMATCH) {
            return null;
        }
        return factsGateway.ledgerPostings().stream()
                .filter(p -> !("ADJUSTMENT".equals(p.sourceType())))
                .filter(p -> p.sourceType().equals(difference.getSourceType())
                        && p.sourceId().equals(difference.getSourceId()))
                .findFirst()
                .map(p -> p.entries().stream()
                        .map(e -> new AdjustmentPolicy.PostingEntry(e.accountId(), e.direction(), e.amountMinor()))
                        .toList())
                .orElse(null);
    }

    private String accountCode(long accountId) {
        return switch ((int) accountId) {
            case 1 -> SuspensePolicy.CUSTOMER_CASH;
            case 2 -> SuspensePolicy.MERCHANT_PAYABLE;
            case 3 -> SuspensePolicy.PLATFORM_FEE_REVENUE;
            case 4 -> SuspensePolicy.SETTLEMENT_PAYABLE;
            case 5 -> SuspensePolicy.SUSPENSE;
            default -> "UNKNOWN";
        };
    }

    private void persistDifferences(AuditBatch batch) {
        for (AuditDifference difference : batch.getDifferences()) {
            auditRepository.saveDifference(batch.getId(), difference);
        }
    }

    private void refreshDispositionSums(AuditBatch batch) {
        long suspended = batch.getDifferences().stream().mapToLong(AuditDifference::getSuspendedAmountMinor).sum();
        long adjusted = batch.getDifferences().stream().mapToLong(AuditDifference::getAdjustedAmountMinor).sum();
        batch.setSuspendedAmountMinor(suspended);
        batch.setAdjustedAmountMinor(adjusted);
    }

    private AuditBatch insertNewBatch(AuditBatch batch) {
        try {
            return auditRepository.insertBatch(batch);
        } catch (DuplicateKeyException e) {
            return auditRepository.findBatchByPeriodAndScope(batch.getPeriod(), batch.getScope())
                    .orElseThrow(() -> BizException.of(ErrorCodes.DUPLICATE,
                            "audit batch duplicate: " + batch.getPeriod() + "/" + batch.getScope()));
        }
    }

    private AuditBatch requireBatch(String batchNo) {
        return auditRepository.findBatchByNo(batchNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "audit batch not found: " + batchNo));
    }

    private AuditDifference requireDifference(AuditBatch batch, Long differenceId) {
        return batch.getDifferences().stream()
                .filter(d -> differenceId.equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "audit difference not found: " + differenceId));
    }

    /** 结算门禁响应（plan §10.3 契约）。 */
    public record SettlementGateResponse(String decision, boolean balanced,
                                         List<BlockingDifference> blockingDifferences) {

        public record BlockingDifference(String kind, String sourceType, String sourceId,
                                         String severity, long amountMinor, String currency) {
        }
    }
}
