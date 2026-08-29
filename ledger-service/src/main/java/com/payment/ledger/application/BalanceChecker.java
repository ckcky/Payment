package com.payment.ledger.application;

import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 全局借贷平衡性校验（FR-007）：按币种聚合 {@code sum(debit) - sum(credit)}，差额应恒为 0。
 *
 * <p>供 reconciliation / 运维校验「账务事实」自洽（SC-004）。</p>
 */
@Service
public class BalanceChecker {

    private final LedgerRepository ledgerRepository;

    public BalanceChecker(LedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    public boolean isBalanced() {
        return byCurrency().values().stream().allMatch(diff -> diff == 0L);
    }

    /** 按币种返回「借方合计 - 贷方合计」的差额；平衡时各币种均为 0。 */
    public Map<String, Long> byCurrency() {
        Map<String, Long> diff = new LinkedHashMap<>();
        for (LedgerEntry entry : ledgerRepository.findAllEntries()) {
            long signed = entry.getDirection() == LedgerEntry.Direction.DEBIT
                    ? entry.getAmountMinor()
                    : -entry.getAmountMinor();
            diff.merge(entry.getCurrency(), signed, Long::sum);
        }
        return diff;
    }

    /** 指定科目（按币种）的余额：借方为正、贷方为负。 */
    public long accountBalance(long accountId, String currency) {
        long balance = 0;
        for (LedgerEntry entry : ledgerRepository.findAllEntries()) {
            if (entry.getAccountId() != accountId || !entry.getCurrency().equals(currency)) {
                continue;
            }
            balance += entry.getDirection() == LedgerEntry.Direction.DEBIT
                    ? entry.getAmountMinor()
                    : -entry.getAmountMinor();
        }
        return balance;
    }

    /** 按来源回查分录（FR-008 追溯）。 */
    public List<LedgerEntry> entriesOfSource(com.payment.ledger.domain.LedgerSourceType sourceType,
                                             String sourceId) {
        return ledgerRepository.findEntriesBySource(sourceType, sourceId);
    }
}
