package com.payment.ledger.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.List;
import java.util.Objects;

/**
 * 记账批次（聚合根，FR-001/FR-002）：一次业务事件对应的一组**借贷平衡**分录。
 *
 * <p>核心不变量：同币种下 {@code sum(DEBIT) == sum(CREDIT)}；不平衡的 Posting MUST 被拒绝，
 * 不落任何分录（数据质量门禁，不是业务错误）。</p>
 */
public class Posting {

    private Long id;
    /** 业务单号（LP + 雪花，ADR-0062）。 */
    private String postingNo;
    private final String idempotencyKey;
    private final LedgerSourceType sourceType;
    private final String sourceId;
    private final String currency;
    private final List<LedgerEntry> entries;
    private Status status = Status.POSTED;

    public Posting(String idempotencyKey, LedgerSourceType sourceType, String sourceId,
                   String currency, List<LedgerEntry> entries) {
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
    public static Posting rehydrate(Long id, String postingNo, String idempotencyKey, LedgerSourceType sourceType,
                                    String sourceId, String currency, Status status,
                                    List<LedgerEntry> entries) {
        Posting posting = new Posting(idempotencyKey, sourceType, sourceId, currency, entries);
        posting.id = id;
        posting.postingNo = postingNo;
        posting.status = status;
        return posting;
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
