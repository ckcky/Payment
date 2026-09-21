package com.payment.ledger.domain.posting.rules;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;

import java.util.List;

/**
 * CHANNEL_FEE 规则（spec 031 §7.3）：渠道费在支付之后才确认时的独立成本事件。
 *
 * <p>与 PAYMENT_CAPTURE.channelFeeMinor 为<b>互斥口径</b>（同一笔费用只走一条路径）；
 * 双路径双计风险登记于 Spec §18（Ledger 侧因键不同无法兜底）。031 交付契约+规则+测试，
 * 真实账单驱动产生链路留 032+。</p>
 */
public class ChannelFeeRule implements PostingRule {

    @Override
    public AccountingEventType eventType() {
        return AccountingEventType.CHANNEL_FEE;
    }

    @Override
    public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
        long fee = event.requireAmount();
        String channel = event.requireChannelCode();
        return List.of(
                PostingLine.debit(AccountCode.CHANNEL_FEE_EXPENSE.name(), channel, fee),
                PostingLine.credit(AccountCode.CHANNEL_RECEIVABLE.name(), channel, fee));
    }
}
