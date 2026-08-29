package com.payment.ledger;

import com.payment.ledger.domain.Account;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerSourceType;

import java.util.ArrayList;
import java.util.List;

/** 记账测试辅助：构造平衡的业务分录组合（见 data-model §5 关键业务映射）。 */
public final class LedgerTestSupport {

    private LedgerTestSupport() {
    }

    /** 支付成功（金额 A，手续费 F，净额 N=A-F）：借客户资金 A / 贷应付商户 N + 贷手续费收入 F。 */
    public static List<LedgerEntry> paymentCapture(LedgerSourceType sourceType, String sourceId,
                                                   long amount, long fee) {
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(entry(sourceType, sourceId, Account.CUSTOMER_CASH, LedgerEntry.Direction.DEBIT,
                amount, LedgerEntry.Type.PAYMENT_CAPTURE));
        entries.add(entry(sourceType, sourceId, Account.MERCHANT_PAYABLE, LedgerEntry.Direction.CREDIT,
                amount - fee, LedgerEntry.Type.PAYMENT_CAPTURE));
        // 与生产网关 FeignLedgerPostingGateway 对齐：手续费为 0 时不产生 0 金额分录。
        if (fee > 0) {
            entries.add(entry(sourceType, sourceId, Account.PLATFORM_FEE_REVENUE,
                    LedgerEntry.Direction.CREDIT, fee, LedgerEntry.Type.FEE));
        }
        return entries;
    }

    /** 退款 R：借应付商户 R / 贷客户资金 R（与支付方向相反）。 */
    public static List<LedgerEntry> refund(LedgerSourceType sourceType, String sourceId, long amount) {
        return List.of(
                entry(sourceType, sourceId, Account.MERCHANT_PAYABLE, LedgerEntry.Direction.DEBIT,
                        amount, LedgerEntry.Type.REFUND),
                entry(sourceType, sourceId, Account.CUSTOMER_CASH, LedgerEntry.Direction.CREDIT,
                        amount, LedgerEntry.Type.REFUND));
    }

    /** 结算 S：借应付商户 S / 贷结算应付 S。 */
    public static List<LedgerEntry> settlement(LedgerSourceType sourceType, String sourceId, long amount) {
        return List.of(
                entry(sourceType, sourceId, Account.MERCHANT_PAYABLE, LedgerEntry.Direction.DEBIT,
                        amount, LedgerEntry.Type.SETTLEMENT),
                entry(sourceType, sourceId, Account.SETTLEMENT_PAYABLE, LedgerEntry.Direction.CREDIT,
                        amount, LedgerEntry.Type.SETTLEMENT));
    }

    public static LedgerEntry entry(LedgerSourceType sourceType, String sourceId, Account account,
                                    LedgerEntry.Direction direction, long amountMinor,
                                    LedgerEntry.Type entryType) {
        return new LedgerEntry(null, account.getId(), direction, amountMinor, "CNY", entryType,
                sourceType, sourceId);
    }
}
