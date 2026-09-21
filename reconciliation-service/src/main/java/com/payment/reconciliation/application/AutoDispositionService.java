package com.payment.reconciliation.application;

import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.audit.application.AuditLedgerGateway;
import com.payment.reconciliation.audit.application.DispositionFailureRecorder;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditAdjustmentKind;
import com.payment.reconciliation.audit.domain.AuditRepository;
import com.payment.reconciliation.domain.AutoDispositionPolicy;
import com.payment.reconciliation.domain.ReconciliationDifference;
import com.payment.reconciliation.domain.ReconciliationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对账差异自动处置（spec 032 G5 / H-032-6，plan §2.7 保守起步）：
 * 对满足 {@link AutoDispositionPolicy#SMALL_CHANNEL_ONLY_SUSPEND} 门（CHANNEL_ONLY / PENDING /
 * CNY / 金额 ≤ 上限）的未收口差异自动挂账——经既有 SUSPEND 计划发 ADJUSTMENT 事件
 * （幂等键 {@code ADJUSTMENT:{adjustNo}} 由 ledger 派生），并写 {@code audit_adjustments}
 * 留痕（batch_id/difference_id 为空 + {@code diff_no} 回溯 recon 差异单号）；成功 ⇒ 差异
 * SUSPENDED + dispositionRef=adjustNo；失败 ⇒ 独立事务写 FAILED 台账 + 差异 ADJUST_FAILED
 * （不静默吞掉）。
 *
 * <p>开关：{@code reconciliation.autodisposition.enabled}（默认 false）+
 * {@code reconciliation.autodisposition.max-amount-minor}（默认 0 = 关闭）。
 * 指标 {@code reconciliation.autodisposition{policy,outcome}}；merchantId/diffNo 禁作标签。</p>
 */
@Service
public class AutoDispositionService {

    private static final Logger log = LoggerFactory.getLogger(AutoDispositionService.class);
    private static final String POLICY = AutoDispositionPolicy.SMALL_CHANNEL_ONLY_SUSPEND.name();

    private final ReconciliationRepository reconciliationRepository;
    private final AuditLedgerGateway ledgerGateway;
    private final AuditRepository auditRepository;
    private final DispositionFailureRecorder failureRecorder;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;
    private final boolean enabled;
    private final long maxAmountMinor;

    public AutoDispositionService(ReconciliationRepository reconciliationRepository,
                                  AuditLedgerGateway ledgerGateway,
                                  AuditRepository auditRepository,
                                  DispositionFailureRecorder failureRecorder,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger,
                                  @Value("${reconciliation.autodisposition.enabled:false}") boolean enabled,
                                  @Value("${reconciliation.autodisposition.max-amount-minor:0}") long maxAmountMinor) {
        this.reconciliationRepository = reconciliationRepository;
        this.ledgerGateway = ledgerGateway;
        this.auditRepository = auditRepository;
        this.failureRecorder = failureRecorder;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.enabled = enabled;
        this.maxAmountMinor = maxAmountMinor;
    }

    /**
     * 对指定批次的差异台账行执行自动处置（幂等：SUSPENDED/RESOLVED 行跳过）。
     * 单条失败不阻断其余差异（§11 #9：失败留痕，人工重试）。
     */
    public void disposeBatch(Long batchId) {
        if (!enabled) {
            return;
        }
        List<ReconciliationDifference> rows = reconciliationRepository.findDifferencesByBatch(batchId);
        for (ReconciliationDifference row : rows) {
            long amount = row.getActualAmountMinor() == null ? 0L : row.getActualAmountMinor();
            if (!AutoDispositionPolicy.SMALL_CHANNEL_ONLY_SUSPEND.eligible(
                    row.getKind(), row.getStatus().name(), amount, row.getCurrency(), maxAmountMinor)) {
                continue;
            }
            dispose(row, amount);
        }
    }

    private void dispose(ReconciliationDifference row, long amount) {
        String adjustNo = BusinessNos.of(BusinessNoType.AUDIT_ADJUSTMENT);
        String reason = "自动挂账：" + POLICY + "（金额≤上限的渠道长款，PENDING→SUSPENDED；diffNo="
                + row.getDiffNo() + "）";
        try {
            // 渠道长款 = 平台账少记（underRecorded）：挂账方向 Dr BANK_CASH / Cr SUSPENSE（plan §2.7）
            AdjustmentPolicy.AdjustPlan plan = AdjustmentPolicy.buildPlan(
                    AuditAdjustmentKind.SUSPEND, true, amount, null, null);
            AuditLedgerGateway.PostingResult result = ledgerGateway.postAdjustment(
                    adjustNo, row.getCurrency(), plan);
            auditRepository.insertAdjustment(new AuditAdjustment(null, adjustNo, null, null,
                    row.getDiffNo(), AuditAdjustmentKind.SUSPEND, plan.toAccountCode(), plan.fromAccountCode(),
                    amount, row.getCurrency(), result.postingNo(), AuditAdjustment.POSTED,
                    "autodisposition", null, reason));
            row.suspend(adjustNo);
            reconciliationRepository.updateDifference(row);
            metrics.counter("reconciliation.autodisposition", 1, "policy", POLICY, "outcome", "succeeded");
            auditLogger.audit("difference_autodispose", adjustNo, amount, row.getCurrency(),
                    "PENDING", "SUSPENDED", "reconciliation", row.getDiffNo());
            log.info("auto-disposition suspended recon difference: diffNo={} amount={} adjustNo={}",
                    row.getDiffNo(), amount, adjustNo);
        } catch (RuntimeException ex) {
            // 独立事务写 FAILED 台账（外层事务可能回滚，留痕必须独立存活）；
            // 差异状态 ADJUST_FAILED 由本方法在外层事务内回写（该行由外层事务写入，独立事务不可见）。
            failureRecorder.record(null, null, row.getDiffNo(), adjustNo, AuditAdjustmentKind.SUSPEND,
                    amount, row.getCurrency(), "autodisposition", reason, ex.getMessage());
            row.markAdjustFailed(adjustNo);
            reconciliationRepository.updateDifference(row);
            metrics.counter("reconciliation.autodisposition", 1, "policy", POLICY, "outcome", "failed");
            log.warn("auto-disposition failed (difference kept visible as ADJUST_FAILED): diffNo={} : {}",
                    row.getDiffNo(), ex.getMessage());
        }
    }
}
