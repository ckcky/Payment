package com.payment.ledger.domain.posting.rules;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;

import java.util.ArrayList;
import java.util.List;

/**
 * PAYMENT_CAPTURE 规则（spec 031 §7.1）：平台代商户收款的毛额三分支。
 *
 * <p>代入案例六（gross=100.00 / merchantFee=0.80 / channelFee=0.60 / M001 / ALIPAY）得 §2 终态。</p>
 */
public class PaymentCaptureRule implements PostingRule {

    @Override
    public AccountingEventType eventType() {
        return AccountingEventType.PAYMENT_CAPTURE;
    }

    @Override
    public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
        long gross = event.requireGross();
        String merchant = event.requireMerchantId();
        String channel = event.requireChannelCode();
        long merchantFee = event.merchantFeeOrZero();
        long channelFee = event.channelFeeOrZero();

        List<PostingLine> lines = new ArrayList<>();
        // 1. 钱在渠道 ⇒ 对渠道应收（毛额）；钱属商户 ⇒ 对商户应付（毛额，不轧差）
        lines.add(PostingLine.debit(AccountCode.CHANNEL_RECEIVABLE.name(), channel, gross));
        lines.add(PostingLine.credit(AccountCode.MERCHANT_PAYABLE.name(), merchant, gross));
        // 2. 商户费：从欠商户的钱里扣，成为平台收入
        if (merchantFee > 0) {
            lines.add(PostingLine.debit(AccountCode.MERCHANT_PAYABLE.name(), merchant, merchantFee));
            lines.add(PostingLine.credit(AccountCode.FEE_REVENUE.name(), null, merchantFee));
        }
        // 3. 渠道费：平台成本，抵减对渠道的应收（清算时渠道实付 gross - channelFee）
        if (channelFee > 0) {
            lines.add(PostingLine.debit(AccountCode.CHANNEL_FEE_EXPENSE.name(), channel, channelFee));
            lines.add(PostingLine.credit(AccountCode.CHANNEL_RECEIVABLE.name(), channel, channelFee));
        }
        return lines;
    }
}
