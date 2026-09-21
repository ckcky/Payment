package com.payment.ledger.domain.posting;

import com.payment.ledger.domain.LedgerEntry;

/**
 * 记账行（值对象，spec 031 §4）：Posting Rule 的输出、AccountResolver 的输入。
 *
 * <p><b>仅存在于 Ledger 内部</b>——031 起不再出现在任何 RPC 契约（原
 * {@code PostingRequest.EntryRequest} 的 accountId+direction 直传形态就此废除）。</p>
 *
 * <p>常规行携带抽象账户三元组（definitionCode + ownerKey）；例外：ADJUSTMENT 红冲
 * 「历史分录取反」的行直接锚定原分录的账户实例（{@code fixedAccountId}），
 * 合法触达 LEGACY 科目（它是对既有事实的更正，不是新事实的记账）。</p>
 */
public record PostingLine(String definitionCode, String ownerKey, LedgerEntry.Direction direction,
                          long amountMinor, Long fixedAccountId) {

    public static PostingLine of(String definitionCode, String ownerKey, LedgerEntry.Direction direction,
                                 long amountMinor) {
        return new PostingLine(definitionCode, ownerKey, direction, amountMinor, null);
    }

    public static PostingLine debit(String definitionCode, String ownerKey, long amountMinor) {
        return of(definitionCode, ownerKey, LedgerEntry.Direction.DEBIT, amountMinor);
    }

    public static PostingLine credit(String definitionCode, String ownerKey, long amountMinor) {
        return of(definitionCode, ownerKey, LedgerEntry.Direction.CREDIT, amountMinor);
    }

    /** 锚定账户实例 id 的行（红冲专用）。 */
    public static PostingLine ofInstance(long accountId, LedgerEntry.Direction direction, long amountMinor) {
        return new PostingLine(null, null, direction, amountMinor, accountId);
    }
}
