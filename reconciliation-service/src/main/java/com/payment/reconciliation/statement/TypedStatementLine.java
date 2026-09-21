package com.payment.reconciliation.statement;

import com.payment.reconciliation.domain.ReconciliationMatching;

/**
 * {@link StatementLine} → 匹配层 {@link ReconciliationMatching.StatementLineView} 适配
 * （领域纯函数不反向依赖聚合实体；lineId 在单批导入内取行号，天然唯一）。
 */
public record TypedStatementLine(StatementLine line) implements ReconciliationMatching.StatementLineView {

    @Override
    public long lineId() {
        return line.lineNo();
    }

    @Override
    public int lineNo() {
        return line.lineNo();
    }

    @Override
    public String channelCode() {
        return line.channelCode();
    }

    @Override
    public String channelTxnNo() {
        return line.channelTxnNo();
    }

    @Override
    public String referenceType() {
        return line.referenceType();
    }

    @Override
    public String reference() {
        return line.reference();
    }

    @Override
    public String merchantId() {
        return line.merchantId();
    }

    @Override
    public long amountMinor() {
        return line.amountMinor();
    }

    @Override
    public long feeMinor() {
        return line.feeMinor();
    }

    @Override
    public String status() {
        return line.status();
    }

    @Override
    public String currency() {
        return line.currency();
    }

    @Override
    public String rawText() {
        return line.rawText();
    }

    @Override
    public String normalizeDefect() {
        return line.normalizeDefect();
    }

    @Override
    public String differenceReference() {
        return line.differenceReference();
    }
}
