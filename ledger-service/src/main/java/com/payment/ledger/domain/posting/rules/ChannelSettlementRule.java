package com.payment.ledger.domain.posting.rules;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;

import java.util.List;

/**
 * CHANNEL_SETTLEMENT 规则（spec 031 §7.4）：钱从渠道进平台银行户、应收核销。
 *
 * <p>031 交付契约+规则+测试（桩产生方）；真实渠道账单驱动留 032+。</p>
 */
public class ChannelSettlementRule implements PostingRule {

    @Override
    public AccountingEventType eventType() {
        return AccountingEventType.CHANNEL_SETTLEMENT;
    }

    @Override
    public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
        long net = event.requireNetSigned();
        String channel = event.requireChannelCode();
        return List.of(
                PostingLine.debit(AccountCode.BANK_CASH.name(), null, net),
                PostingLine.credit(AccountCode.CHANNEL_RECEIVABLE.name(), channel, net));
    }
}
