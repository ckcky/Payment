package com.payment.ledger.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerPeriodRepository;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRuleRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 记账引擎（spec 031 §4 目标管道的中枢，原则 5/6/10）：
 * 事件 → PostingRule 展开 → AccountResolver 解析 → {@link Posting} 聚合根构造期平衡门禁
 * → 期间（关账）门禁 → 单事务落库（交易 + 分录 + 余额投影）。
 *
 * <p>幂等（原则 10）：幂等键由 Ledger 按 {@code {eventType}:{sourceId}} 派生，
 * 先回查后插入；并发撞 {@code uk_postings_idempotency_key / uk_event_source} 时捕获
 * {@link DuplicateKeyException} 回查返回首次结果——重复事件永不产生重复分录。</p>
 */
@Service
public class PostingEngine {

    private static final String MODULE = "ledger";

    private final LedgerRepository ledgerRepository;
    private final PostingRuleRegistry ruleRegistry;
    private final AccountResolver accountResolver;
    private final LedgerPeriodRepository periodRepository;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public PostingEngine(LedgerRepository ledgerRepository,
                         PostingRuleRegistry ruleRegistry,
                         AccountResolver accountResolver,
                         LedgerPeriodRepository periodRepository,
                         BusinessMetrics metrics,
                         StructuredAuditLogger auditLogger) {
        this.ledgerRepository = ledgerRepository;
        this.ruleRegistry = ruleRegistry;
        this.accountResolver = accountResolver;
        this.periodRepository = periodRepository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /** 处理一个已确认财务事件：返回新落库或幂等回放的交易。 */
    public Posting post(AccountingEvent event) {
        String key = event.derivedIdempotencyKey();
        Posting existing = ledgerRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            metrics.counter("ledger.duplicate", 1.0, "module", MODULE,
                    "eventType", event.eventType().name());
            return existing;
        }

        List<PostingLine> lines = ruleRegistry.require(event.eventType())
                .expand(event, ledgerRepository::findByEvent);
        List<LedgerEntry> entries = lines.stream()
                .map(line -> new LedgerEntry(null, accountResolver.resolve(line, event.currency()),
                        line.direction(), line.amountMinor(), event.currency()))
                .toList();

        // 聚合根构造期即做借贷平衡校验：不平衡直接拒绝，不落任何分录（FR-002 门禁保留）
        Posting posting = new Posting(event.eventType(), key, event.sourceType(),
                event.sourceId(), event.currency(), entries);
        posting.stampPostedAt(Instant.now());

        if (periodRepository.isClosed(posting.getPeriod(), posting.getCurrency())) {
            throw BizException.of(ErrorCodes.PERIOD_CLOSED,
                    "period closed: " + posting.getPeriod() + "/" + posting.getCurrency());
        }

        Posting saved;
        try {
            saved = ledgerRepository.save(posting);
        } catch (DuplicateKeyException e) {
            metrics.counter("ledger.duplicate", 1.0, "module", MODULE,
                    "eventType", event.eventType().name());
            return ledgerRepository.findByIdempotencyKey(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "ledger posting duplicate but not found: " + key));
        }
        metrics.counter("ledger.posted", 1.0, "module", MODULE,
                "eventType", event.eventType().name(), "source", event.sourceType().name());
        audit(saved);
        return saved;
    }

    /** 成功记账写入资金审计（FR-011）：来源、金额、账户实例与分录摘要。 */
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
