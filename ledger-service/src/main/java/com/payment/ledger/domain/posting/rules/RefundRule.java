package com.payment.ledger.domain.posting.rules;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;

import java.util.ArrayList;
import java.util.List;

/**
 * REFUND 规则（spec 031 §7.2）：退款由渠道清算额中扣还 ⇒ 应收/应付同时冲减。
 * 迁移后不再触碰平台现金科目（CUSTOMER_CASH 判 LEGACY）。
 *
 * <p>费用退还（rule 2/3）仅当上游确认退（merchantFeeMinor / channelFeeMinor > 0）才发生——
 * Ledger 不猜是否退。</p>
 */
public class RefundRule implements PostingRule {

    @Override
    public AccountingEventType eventType() {
        return AccountingEventType.REFUND;
    }

    @Override
    public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
        long gross = event.requireGross();
        String merchant = event.requireMerchantId();
        String channel = event.requireChannelCode();
        long feeRefund = event.merchantFeeOrZero();
        long costRefund = event.channelFeeOrZero();

        List<PostingLine> lines = new ArrayList<>();
        // 1. 应付冲减 / 应收冲减
        lines.add(PostingLine.debit(AccountCode.MERCHANT_PAYABLE.name(), merchant, gross));
        lines.add(PostingLine.credit(AccountCode.CHANNEL_RECEIVABLE.name(), channel, gross));
        // 2. 商户费退还：收入减少、欠商户增加（仅当上游确认退）
        if (feeRefund > 0) {
            lines.add(PostingLine.debit(AccountCode.FEE_REVENUE.name(), null, feeRefund));
            lines.add(PostingLine.credit(AccountCode.MERCHANT_PAYABLE.name(), merchant, feeRefund));
        }
        // 3. 渠道费退回：成本减少、应收增加（仅当上游确认退）
        if (costRefund > 0) {
            lines.add(PostingLine.debit(AccountCode.CHANNEL_RECEIVABLE.name(), channel, costRefund));
            lines.add(PostingLine.credit(AccountCode.CHANNEL_FEE_EXPENSE.name(), channel, costRefund));
        }
        return lines;
    }
}
