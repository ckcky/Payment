package com.payment.ledger.application;

import com.payment.ledger.domain.AccountBalance;
import com.payment.ledger.domain.AccountBalanceRepository;
import com.payment.ledger.domain.AccountDefinition;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountRepository;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.TrialBalanceRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 平衡性校验与余额视图（FR-007 / spec 031 §10，G1）：
 * 试算平衡恒按**分录**（Source of Truth）聚合，投影只作展示读模型。
 *
 * <p>{@code balance} 对外按科目正常余额方向取号（借余 = 借-贷；贷余 = 贷-借）。</p>
 */
@Service
public class BalanceChecker {

    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    private final AccountBalanceRepository balanceRepository;

    public BalanceChecker(LedgerRepository ledgerRepository,
                          AccountRepository accountRepository,
                          AccountBalanceRepository balanceRepository) {
        this.ledgerRepository = ledgerRepository;
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
    }

    /** 全局试算平衡（FR-007）：各币种 Σ借 - Σ贷 恒为 0。 */
    public boolean isBalanced() {
        return byCurrency().values().stream().allMatch(diff -> diff == 0L);
    }

    /** 按币种返回「借方合计 - 贷方合计」差额（全期间，按分录聚合）。 */
    public Map<String, Long> byCurrency() {
        return byCurrency(null);
    }

    /** 按币种差额（G2）：period 非空时仅统计该期间发生额。 */
    public Map<String, Long> byCurrency(String period) {
        Map<String, Long> diff = new LinkedHashMap<>();
        for (TrialBalanceRow row : ledgerRepository.trialBalance(period)) {
            diff.merge(row.currency(), row.diff(), Long::sum);
        }
        return diff;
    }

    /** 试算平衡表（G1/G2）：period=null 为全期间；返回科目级发生额与取号余额。 */
    public List<TrialBalanceView> trialBalance(String period) {
        return ledgerRepository.trialBalance(period).stream()
                .map(row -> {
                    AccountInstance instance = accountRepository.findInstanceById(row.accountId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "entry references unknown account instance: " + row.accountId()));
                    AccountDefinition definition = requireDefinition(instance.getDefinitionCode());
                    return new TrialBalanceView(instance.getId(), definition.getCode(),
                            definition.getName(), instance.getOwnerType().name(), instance.getOwnerId(),
                            row.currency(), row.debitTotal(), row.creditTotal(),
                            sign(row, definition));
                })
                .toList();
    }

    /** 分户余额视图（G1 端点数据源）：读投影，缺行 = 零余额。 */
    public BalanceView balanceOf(long accountInstanceId, String currency) {
        AccountInstance instance = accountRepository.findInstanceById(accountInstanceId)
                .orElseThrow(() -> new IllegalStateException("unknown account instance: " + accountInstanceId));
        AccountDefinition definition = requireDefinition(instance.getDefinitionCode());
        AccountBalance balance = balanceRepository.find(accountInstanceId, currency).orElse(null);
        return toView(instance, definition, balance);
    }

    /** 全部账户实例余额（可按币种过滤）。 */
    public List<BalanceView> balances(String currency) {
        return accountRepository.findAllInstances().stream()
                .filter(i -> currency == null || currency.isBlank() || currency.equals(i.getCurrency()))
                .map(i -> toView(i, requireDefinition(i.getDefinitionCode()),
                        balanceRepository.find(i.getId(), i.getCurrency()).orElse(null)))
                .toList();
    }

    private BalanceView toView(AccountInstance instance, AccountDefinition definition,
                               AccountBalance balance) {
        long debit = balance == null ? 0L : balance.getDebitTotal();
        long credit = balance == null ? 0L : balance.getCreditTotal();
        long count = balance == null ? 0L : balance.getEntryCount();
        long signed = definition.getNormalBalance() == AccountDefinition.NormalSide.DEBIT
                ? debit - credit
                : credit - debit;
        return new BalanceView(instance.getId(), definition.getCode(), definition.getName(),
                definition.getNormalBalance().name(), instance.getOwnerType().name(),
                instance.getOwnerId(), balance == null ? instance.getCurrency() : balance.getCurrency(),
                debit, credit, signed, count);
    }

    private long sign(TrialBalanceRow row, AccountDefinition definition) {
        return definition.getNormalBalance() == AccountDefinition.NormalSide.DEBIT
                ? row.diff()
                : -row.diff();
    }

    private AccountDefinition requireDefinition(String code) {
        return accountRepository.findDefinition(code)
                .orElseThrow(() -> new IllegalStateException("unknown account definition: " + code));
    }

    /** 试算平衡表行（科目 × 币种）。 */
    public record TrialBalanceView(Long accountId, String accountCode, String accountName,
                                   String ownerType, String ownerId, String currency,
                                   long debitTotal, long creditTotal, long balance) {
    }

    /** 分户余额视图行。 */
    public record BalanceView(Long accountId, String accountCode, String accountName,
                              String normalBalance, String ownerType, String ownerId, String currency,
                              long debitTotal, long creditTotal, long balance, long entryCount) {
    }
}
