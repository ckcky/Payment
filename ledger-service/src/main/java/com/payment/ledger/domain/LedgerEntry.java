package com.payment.ledger.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.Objects;

/**
 * 分录（不可变，append-only；FR-003 / spec 031 §9）：单条借贷记录。
 *
 * <p>已提交分录 MUST NOT UPDATE/DELETE；更正的唯一路径是**新的 ADJUSTMENT 事件**。</p>
 *
 * <p>031 收敛：分录不再自带 {@code entry_type / source_type / source_id}（三列停写停读，
 * 事件语义由 {@code ledger_transactions.event_type} 承载，追溯走 posting_id join）。
 * {@code accountId} 自 031 起指向**账户实例**（{@link AccountInstance}，ADR-0078）。</p>
 */
public class LedgerEntry {

    private Long id;
    private final Long postingId;
    private final long accountId;
    private final Direction direction;
    private final long amountMinor;
    private final String currency;

    public LedgerEntry(Long postingId, long accountId, Direction direction, long amountMinor,
                       String currency) {
        this.postingId = postingId;
        this.accountId = accountId;
        this.direction = Objects.requireNonNull(direction, "direction");
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "ledger entry amount must be > 0");
        }
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /** 持久化重建（不可变聚合的还原入口）。 */
    public static LedgerEntry rehydrate(Long id, Long postingId, long accountId, Direction direction,
                                        long amountMinor, String currency) {
        LedgerEntry entry = new LedgerEntry(postingId, accountId, direction, amountMinor, currency);
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

    /** 借贷方向。 */
    public enum Direction {
        DEBIT,
        CREDIT
    }
}
