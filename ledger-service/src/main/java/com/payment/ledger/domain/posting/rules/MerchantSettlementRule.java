package com.payment.ledger.domain.posting.rules;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;

import java.util.List;

/**
 * MERCHANT_SETTLEMENT 规则（spec 031 §7.5 + ADR-0023 + H3）：应付转已结算。
 *
 * <p>net>0：{@code Dr MERCHANT_PAYABLE:M / Cr SETTLEMENT_PAYABLE:M}（结转）。
 * net<0（H3/C-07）：反向 {@code Dr SETTLEMENT_PAYABLE:M / Cr MERCHANT_PAYABLE:M |net|}
 * （商户倒欠，下期净额天然抵减），替代现状「net≤0 不记账」造成的科目残留。
 * net=0：引擎在展开前以 {@code EVENT_FIELD_MISSING} 拒绝（空批次不记账，见 {@code PostingEngine}）。</p>
 *
 * <p>注：SETTLEMENT_PAYABLE 的 owner 维度取 §5.1 表（MERCHANT），非 §5.2 示意图（PLATFORM）——
 * 二者在 Spec 内冲突，以 §5.1 逐科目表 + §7.5 记法 {@code SETTLEMENT_PAYABLE:M} 为准（登记于 §20 文档同步）。</p>
 */
public class MerchantSettlementRule implements PostingRule {

    @Override
    public AccountingEventType eventType() {
        return AccountingEventType.MERCHANT_SETTLEMENT;
    }

    @Override
    public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
        long net = event.requireNetSigned();
        String merchant = event.requireMerchantId();
        if (net == 0) {
            // 空批次不记账（§7.5）：调用方不应发 net=0 事件，账本兜底拒绝而非静默空交易
            throw com.payment.common.core.error.BizException.of(
                    com.payment.common.core.error.ErrorCodes.EVENT_FIELD_MISSING,
                    "MERCHANT_SETTLEMENT net must be non-zero (empty batch: no event)");
        }
        if (net > 0) {
            return List.of(
                    PostingLine.debit(AccountCode.MERCHANT_PAYABLE.name(), merchant, net),
                    PostingLine.credit(AccountCode.SETTLEMENT_PAYABLE.name(), merchant, net));
        }
        // H3：负净额反向分录
        long abs = Math.abs(net);
        return List.of(
                PostingLine.debit(AccountCode.SETTLEMENT_PAYABLE.name(), merchant, abs),
                PostingLine.credit(AccountCode.MERCHANT_PAYABLE.name(), merchant, abs));
    }
}
