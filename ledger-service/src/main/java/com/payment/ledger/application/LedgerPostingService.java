package com.payment.ledger.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 记账应用服务（FR-001/002/004/011）：校验平衡 → 幂等回查 → 落库 → 资金审计。
 *
 * <p>幂等：先按幂等键回查，未命中再插入；并发/重启后的重复插入撞
 * {@code uk_postings_idempotency_key} 唯一约束时捕获并回查返回首次结果（不重复入账）。</p>
 *
 * <p>借贷不平衡由 {@link Posting} 聚合根在构造期拒绝（{@code LEDGER_UNBALANCED}），
 * 不落任何分录（数据质量门禁）。</p>
 */
@Service
public class LedgerPostingService {

    private static final String MODULE = "ledger";

    private final LedgerRepository ledgerRepository;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public LedgerPostingService(LedgerRepository ledgerRepository,
                                BusinessMetrics metrics,
                                StructuredAuditLogger auditLogger) {
        this.ledgerRepository = ledgerRepository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 记账：返回已存在的或新落库的 Posting；借贷不平衡直接抛错拒绝。
     *
     * @param idempotencyKey 幂等键（业务提供，如 {@code PAYMENT:<paymentId>}）
     * @param sourceType     来源类型
     * @param sourceId       来源 ID
     * @param currency       币种（MVP 仅 CNY）
     * @param entries        分录（借贷必须平衡）
     */
    public Posting post(String idempotencyKey, LedgerSourceType sourceType, String sourceId,
                        String currency, List<LedgerEntry> entries) {
        return ledgerRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> insert(idempotencyKey, sourceType, sourceId, currency, entries));
    }

    private Posting insert(String idempotencyKey, LedgerSourceType sourceType, String sourceId,
                           String currency, List<LedgerEntry> entries) {
        // 聚合根构造期即做借贷平衡校验：不平衡直接拒绝，不落任何分录（FR-002）
        Posting posting = new Posting(idempotencyKey, sourceType, sourceId, currency, entries);
        Posting saved;
        try {
            saved = ledgerRepository.save(posting);
        } catch (DuplicateKeyException e) {
            metrics.counter("ledger.duplicate", 1.0, "module", MODULE);
            return ledgerRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "ledger posting duplicate but not found: " + idempotencyKey));
        }
        metrics.counter("ledger.posted", 1.0, "module", MODULE, "source", sourceType.name());
        audit(saved);
        return saved;
    }

    /** 成功记账写入资金审计（FR-011）：来源、金额、科目与分录摘要。 */
    private void audit(Posting posting) {
        long total = posting.getEntries().stream().mapToLong(LedgerEntry::getAmountMinor).sum();
        String accounts = posting.getEntries().stream()
                .map(e -> e.getDirection() + ":" + e.getAccountId() + ":" + e.getAmountMinor())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        auditLogger.audit("ledger.posted", posting.getIdempotencyKey(), total, posting.getCurrency(),
                posting.getSourceType().name(), posting.getStatus().name(), "posting",
                String.valueOf(posting.getId()));
        auditLogger.audit("ledger.entries", posting.getIdempotencyKey(), total, posting.getCurrency(),
                accounts, posting.getStatus().name(), "posting", String.valueOf(posting.getId()));
    }
}
