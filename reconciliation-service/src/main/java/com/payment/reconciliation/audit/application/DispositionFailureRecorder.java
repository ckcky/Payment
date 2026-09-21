package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditAdjustment;
import com.payment.reconciliation.audit.domain.AuditAdjustmentKind;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceStatus;
import com.payment.reconciliation.audit.domain.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置失败留痕（spec 032 §11 #9 / H-032-3）：记账失败时以<b>独立事务（REQUIRES_NEW）</b>
 * 写 FAILED 处置台账 + 差异置 {@code ADJUST_FAILED}，然后由调用方原样上抛。
 *
 * <p>为什么独立事务：外层处置事务即将因异常回滚——失败留痕若同事务会一并蒸发（静默吞掉），
 * 违反「不静默」红线。audit 侧外层此刻尚未写过差异行，独立事务可安全读写（无锁冲突）。</p>
 *
 * <p>两种调用形态：①audit 侧人工处置（batchId/differenceId 非空，差异行同步置 ADJUST_FAILED）；
 * ②recon 侧自动处置（batchId/differenceId 为空 + diffNo 回溯；差异状态由调用方在外层事务里置
 * ADJUST_FAILED——该行由外层事务插入，独立事务不可见，锁语义不允许跨事务更新）。</p>
 */
@Component
public class DispositionFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(DispositionFailureRecorder.class);

    private final AuditRepository auditRepository;

    public DispositionFailureRecorder(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /** 独立事务：FAILED 台账行（+ audit 侧差异行 ADJUST_FAILED）。失败留痕自身异常不掩盖原始异常。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long batchId, Long differenceId, String diffNo, String adjustNo,
                       AuditAdjustmentKind kind, long amountMinor, String currency,
                       String operator, String reason, String error) {
        try {
            AuditAdjustment adjustment = new AuditAdjustment(null, adjustNo, batchId, differenceId, diffNo,
                    kind, "FAILED", "FAILED", amountMinor, currency, null,
                    AuditAdjustment.FAILED, operator, null,
                    "处置失败：" + (reason == null ? "" : reason) + " / " + error);
            auditRepository.insertAdjustment(adjustment);
            if (differenceId != null) {
                auditRepository.findDifferenceById(differenceId).ifPresent(difference -> {
                    difference.setStatus(AuditDifferenceStatus.ADJUST_FAILED);
                    auditRepository.saveDifference(difference.getBatchId(), difference);
                });
            }
            log.warn("disposition failure recorded (independent tx): adjustNo={} diffNo={} difference={} kind={} error={}",
                    adjustNo, diffNo, differenceId, kind, error);
        } catch (RuntimeException recordEx) {
            // 留痕自身失败绝不掩盖原始异常：记日志后放行调用方上抛。
            log.error("disposition failure recording failed: adjustNo={} difference={} : {}",
                    adjustNo, differenceId, recordEx.getMessage());
        }
    }
}
