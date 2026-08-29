package com.payment.ledger.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.Objects;

/**
 * 分录（不可变，append-only；FR-003）：单条借贷记录。
 *
 * <p>已提交分录 MUST NOT UPDATE/DELETE；更正只能新增反向分录（冲正），与业务退款同机制。</p>
 */
public class LedgerEntry {

    private Long id;
    private final Long postingId;
    private final long accountId;
    private final Direction direction;
    private final long amountMinor;
    private final String currency;
    private final Type entryType;
    private final LedgerSourceType sourceType;
    private final String sourceId;

    public LedgerEntry(Long postingId, long accountId, Direction direction, long amountMinor,
                       String currency, Type entryType, LedgerSourceType sourceType, String sourceId) {
        this.postingId = postingId;
        this.accountId = accountId;
        this.direction = Objects.requireNonNull(direction, "direction");
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "ledger entry amount must be > 0");
        }
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    /** 持久化重建（不可变聚合的还原入口）。 */
    public static LedgerEntry rehydrate(Long id, Long postingId, long accountId, Direction direction,
                                        long amountMinor, String currency, Type entryType,
                                        LedgerSourceType sourceType, String sourceId) {
        LedgerEntry entry = new LedgerEntry(postingId, accountId, direction, amountMinor, currency,
                entryType, sourceType, sourceId);
        entry.id = id;
        return entry;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Long getPostingId() {
        return postingId;
    }

    public long getAccountId() {
        return accountId;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Type getEntryType() {
        return entryType;
    }

    public LedgerSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    /** 借贷方向。 */
    public enum Direction {
        DEBIT,
        CREDIT
    }

    /** 分录业务类型。 */
    public enum Type {
        PAYMENT_CAPTURE,
        FEE,
        REFUND,
        SETTLEMENT
    }
}
