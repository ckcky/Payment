package com.payment.ledger.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.common.dto.rpc.AccountingEventType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 账本交易（LedgerTransaction，聚合根 {@code postings} 表演进，spec 031 §9 / ADR-0077）：
 * 一个 Accounting Event 对应的一组**借贷平衡**分录（事件 1:1 交易、1:N 分录）。
 *
 * <p>核心不变量（保留自 ADR-0008，不因两层结构改变）：同币种下 {@code sum(DEBIT) == sum(CREDIT)}；
 * 不平衡的 Posting MUST 被拒绝，不落任何分录（数据质量门禁，不是业务错误）。</p>
 *
 * <p>幂等键由 Ledger 派生（{@code {eventType}:{sourceId}}，原则 10），与
 * {@code uk_event_source(event_type, source_id)} 构成双唯一约束。</p>
 */
public class Posting {

    private Long id;
    /** 业务单号（LP + 雪花，ADR-0062；列 posting_no 同义演进为 transaction_no 语义）。 */
    private String postingNo;
    private final AccountingEventType eventType;
    private final String idempotencyKey;
    private final LedgerSourceType sourceType;
    private final String sourceId;
    private final String currency;
    /** 会计期间 YYYY-MM（G2：落库按 postedAt 派生，NOT NULL）。 */
    private String period;
    private final List<LedgerEntry> entries;
    private Instant postedAt;
    private Status status = Status.POSTED;

    public Posting(AccountingEventType eventType, String idempotencyKey, LedgerSourceType sourceType,
                   String sourceId, String currency, List<LedgerEntry> entries) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.postingNo = BusinessNos.of(BusinessNoType.LEDGER_POSTING);
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (this.entries.size() < 2) {
            throw BizException.of(ErrorCodes.LEDGER_UNBALANCED,
                    "posting requires at least 2 entries");
        }
        requireBalanced();
    }

    /** 持久化重建：绕过创建期校验以外的业务规则不变（重建后仍校验平衡）。 */
    public static Posting rehydrate(Long id, String postingNo, AccountingEventType eventType,
                                    String idempotencyKey, LedgerSourceType sourceType,
                                    String sourceId, String currency, String period,
                                    Instant postedAt, Status status,
                                    List<LedgerEntry> entries) {
        Posting posting = new Posting(eventType, idempotencyKey, sourceType, sourceId, currency, entries);
        posting.id = id;
        posting.postingNo = postingNo;
        posting.period = period;
        posting.postedAt = postedAt;
        posting.status = status;
        return posting;
    }

    /** 落库前补齐期间/时刻（按系统时区的 YYYY-MM，spec §11）。 */
    public void stampPostedAt(Instant at) {
        this.postedAt = at;
        this.period = java.time.YearMonth.from(at.atZone(java.time.ZoneId.systemDefault())).toString();
    }

    /** 借贷平衡校验（同币种）：不平衡直接拒绝，不落任何分录。 */
    public boolean isBalanced() {
        long debit = 0;
        long credit = 0;
        for (LedgerEntry entry : entries) {
            if (!currency.equals(entry.getCurrency())) {
                throw BizException.of(ErrorCodes.LEDGER_UNBALANCED,
                        "posting currency mismatch: " + entry.getCurrency());
            }
            if (entry.getDirection() == LedgerEntry.Direction.DEBIT) {
                debit += entry.getAmountMinor();
            } else {
                credit += entry.getAmountMinor();
            }
        }
        return debit == credit;
    }

    private void requireBalanced() {
        if (!isBalanced()) {
            throw BizException.of(ErrorCodes.LEDGER_UNBALANCED,
                    "posting is not balanced for source " + sourceType + ":" + sourceId);
        }
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPostingNo() {
        return postingNo;
    }

    public Long getId() {
        return id;
    }

    public AccountingEventType getEventType() {
        return eventType;
    }

    public String getPeriod() {
        return period;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public LedgerSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getCurrency() {
        return currency;
    }

    public List<LedgerEntry> getEntries() {
        return entries;
    }

    public Status getStatus() {
        return status;
    }

    /** 记账状态：MVP 仅 POSTED。 */
    public enum Status {
        PENDING,
        POSTED
    }
}
