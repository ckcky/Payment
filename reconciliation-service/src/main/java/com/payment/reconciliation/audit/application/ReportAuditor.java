package com.payment.reconciliation.audit.application;

import com.payment.reconciliation.audit.domain.AuditDifference;
import com.payment.reconciliation.audit.domain.AuditDifferenceKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A4 账表核对（spec 017 / FR-009）：对外报表口径（006 对账批次的匹配汇总，即对账结果表）
 * ↔ 业务回算（已确认事实）。该周期无 006 批次则无可比报表，跳过（不产差异）。
 */
@Component
public class ReportAuditor {

    /**
     * @param reportMatches   006 对账批次的匹配结果（reference, type, amountMinor）——「报表」口径
     * @param confirmedFacts  业务回算口径
     */
    public List<AuditDifference> audit(List<MatchView> reportMatches, List<CertificateFact> confirmedFacts) {
        List<AuditDifference> differences = new ArrayList<>();
        if (reportMatches == null || reportMatches.isEmpty()) {
            return differences;
        }
        long reportPayment = reportMatches.stream()
                .filter(m -> "PAYMENT".equals(m.type())).mapToLong(MatchView::amountMinor).sum();
        long reportRefund = reportMatches.stream()
                .filter(m -> "REFUND".equals(m.type())).mapToLong(MatchView::amountMinor).sum();
        long factPayment = confirmedFacts.stream()
                .filter(f -> f.confirmed() && "PAYMENT".equals(f.sourceType())).mapToLong(CertificateFact::amountMinor).sum();
        long factRefund = confirmedFacts.stream()
                .filter(f -> f.confirmed() && "REFUND".equals(f.sourceType())).mapToLong(CertificateFact::amountMinor).sum();

        if (reportPayment != factPayment) {
            differences.add(AuditDifference.of(AuditDifferenceKind.REPORT_MISMATCH, "REPORT", "PAYMENT_SUBTOTAL",
                    null, reportPayment, factPayment, "CNY",
                    "报表支付合计 " + reportPayment + " / 业务回算 " + factPayment));
        }
        if (reportRefund != factRefund) {
            differences.add(AuditDifference.of(AuditDifferenceKind.REPORT_MISMATCH, "REPORT", "REFUND_SUBTOTAL",
                    null, reportRefund, factRefund, "CNY",
                    "报表退款合计 " + reportRefund + " / 业务回算 " + factRefund));
        }
        return differences;
    }

    /** 报表口径的匹配行视图（来自 006 reconciliation_batches.matches）。 */
    public record MatchView(String reference, String type, long amountMinor, String currency) {
    }
}
