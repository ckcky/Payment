package com.payment.reconciliation.audit.application;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import com.payment.reconciliation.statement.StatementLine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A3 账实核对（spec 017 / FR-008 + spec 032 plan §2.9 typed 升级）：账本资金科目发生额 ↔
 * 导入的标准账单行。与既有「业务台账 ↔ 渠道账单」链路并列（006 链路保持不动，NFR-006）。
 *
 * <p>口径：按渠道引用（channelReference）比对账本**资金腿**带符号发生额——
 * 支付为正、退款为负；账本无对应发生额（长款）或金额不符均报差异。</p>
 *
 * <p>032 升级（typed 输入 = {@link StatementLine}，替代 legacy 4 字段快照）：</p>
 * <ul>
 *   <li>行方向由 {@code referenceType} 显式给出（REFUND 为负），不再靠事实侧反查；</li>
 *   <li>FEE / SETTLEMENT 行不参与资金腿比对（分别由 CHANNEL_FEE / CHANNEL_SETTLEMENT
 *       事件与账本对平，避免双计）；</li>
 *   <li>行状态非 SUCCEEDED 而<b>平台事实已确认且账本有资金移动</b> ⇒ 可查询差异
 *       （吸收历史「静默跳过」，spec 032 §1.2 证据 13）；</li>
 *   <li>重复渠道流水（同引用多行）仍逐条留痕。</li>
 * </ul>
 *
 * <p>spec 031：资金腿科目集 = CUSTOMER_CASH（历史）/ BANK_CASH（新现金腿）/
 * CHANNEL_RECEIVABLE（支付事件的应收腿，费 0 口径下净额 = gross，与账单可对）。</p>
 */
@Component
public class RealAuditor {

    private static final String[] FUND_CODES = {
            AccountCode.CUSTOMER_CASH.name(), AccountCode.BANK_CASH.name(),
            AccountCode.CHANNEL_RECEIVABLE.name()};

    /**
     * @param facts      已确认业务事实（含渠道引用，用于把 posting 关联到渠道口径）
     * @param lines      标准化账单行（032 导入台账）
     * @param postings   账本全部分录
     * @param officialFile 账单口径——true = 存在周期正式导入（双向比对：正向 + 反向短款 + 重复流水）；
     *                     false 仅正向比对（避免对历史账本全量误报短款）
     */
    public List<AuditDifference> audit(List<CertificateFact> facts,
                                       List<StatementLine> lines,
                                       List<LedgerPostingView> postings,
                                       boolean officialFile) {
        List<AuditDifference> differences = new ArrayList<>();

        // 渠道引用 → 账本资金科目带符号发生额（经由业务事实把 posting.sourceId 关联到 reference）
        Map<String, Long> ledgerByReference = new HashMap<>();
        Map<String, CertificateFact> factBySource = new HashMap<>();
        Set<String> confirmedReferences = new HashSet<>();
        for (CertificateFact fact : facts) {
            if (fact.confirmed() && fact.reference() != null) {
                factBySource.put(fact.sourceType() + "|" + fact.sourceId(), fact);
                confirmedReferences.add(fact.reference());
            }
        }
        for (LedgerPostingView posting : postings) {
            CertificateFact fact = factBySource.get(posting.sourceType() + "|" + posting.sourceId());
            if (fact == null) {
                continue;
            }
            ledgerByReference.merge(fact.reference(),
                    posting.signedForCodes(FUND_CODES), Long::sum);
        }

        // 正向：账单逐行 ↔ 账本；同引用多行（重复投递形态）单独报差异
        Map<String, Integer> lineCountByReference = new HashMap<>();
        for (StatementLine line : lines) {
            String reference = line.reference() != null ? line.reference() : line.channelTxnNo();
            if (reference == null) {
                continue; // 无法归一行由对账侧 UNKNOWN_MAPPING 承载（不重复计数）
            }
            if ("FEE".equals(line.referenceType()) || "SETTLEMENT".equals(line.referenceType())) {
                continue; // FEE/SETTLEMENT 行不进资金腿比对（G3 事件对平，双计防线）
            }
            if (!"SUCCEEDED".equals(line.status())) {
                // 静默跳过 → 可查询：渠道行未成功而平台已确认且账本有资金移动 ⇒ 明示差异
                long ledgerAmount = ledgerByReference.getOrDefault(reference, 0L);
                if (ledgerAmount != 0L && confirmedReferences.contains(reference)) {
                    differences.add(AuditDifference.of(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK, "CHANNEL",
                            reference, reference, 0L, ledgerAmount, line.currency(),
                            "账实不符：渠道账单行状态 " + line.status() + "（非 SUCCEEDED）而账本资金科目 "
                                    + ledgerAmount + "（平台事实已确认）"));
                }
                continue;
            }
            lineCountByReference.merge(reference, 1, Integer::sum);
            long ledgerAmount = ledgerByReference.getOrDefault(reference, 0L);
            // 渠道账单口径：支付为正、退款为负（typed 行 direction 由 referenceType 显式给出）
            long statementSigned = "REFUND".equals(line.referenceType())
                    ? -line.amountMinor() : line.amountMinor();
            if (ledgerAmount != statementSigned) {
                differences.add(AuditDifference.of(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK, "CHANNEL",
                        reference, reference, statementSigned, ledgerAmount, line.currency(),
                        "账实不符：渠道账单 " + statementSigned + " / 账本资金科目 " + ledgerAmount));
            }
        }
        // 重复渠道流水（同引用多行，无论金额是否与账本一致都必须留痕）
        for (Map.Entry<String, Integer> e : lineCountByReference.entrySet()) {
            if (e.getValue() > 1) {
                differences.add(AuditDifference.of(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK, "CHANNEL",
                        e.getKey(), e.getKey(), 0L, ledgerByReference.getOrDefault(e.getKey(), 0L), "CNY",
                        "重复渠道流水：同引用出现 " + e.getValue() + " 行账单（重复投递/重出账单）"));
            }
        }

        // 反向：正式账单周期内，账本有发生额而账单缺行（短款/单边账）。
        // 仅在命中周期正式导入时开启——无导入不代表当期账单全集，对历史账本全量反向会误报。
        if (officialFile) {
            Set<String> statementRefs = new HashSet<>(lineCountByReference.keySet());
            for (Map.Entry<String, Long> e : ledgerByReference.entrySet()) {
                if (!statementRefs.contains(e.getKey()) && e.getValue() != 0L) {
                    CertificateFact fact = facts.stream()
                            .filter(f -> f.confirmed() && e.getKey().equals(f.reference()))
                            .findFirst().orElse(null);
                    differences.add(AuditDifference.of(AuditDifferenceKind.LEDGER_VS_STATEMENT_BREAK, "CHANNEL",
                            e.getKey(), e.getKey(), 0L, e.getValue(),
                            fact == null ? "CNY" : fact.currency(),
                            "账实不符：账本资金科目 " + e.getValue() + " / 渠道账单缺行（短款/单边账）"));
                }
            }
        }
        return differences;
    }
}
