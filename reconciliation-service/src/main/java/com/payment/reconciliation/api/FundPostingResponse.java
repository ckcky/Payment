package com.payment.reconciliation.api;

import com.payment.reconciliation.application.ChannelFundPostingService;
import com.payment.reconciliation.domain.ChannelFundFact;

import java.util.List;

/**
 * 渠道资金事实入账响应（spec §10.1 G3）：聚合口径 + 实际发出的 ledger 事件
 * （零净额跳过不发空事件；sourceId 确定性派生，重放幂等）。
 */
public record FundPostingResponse(String channelCode, String period, long netReceivedMinor,
                                  long channelFeeMinor, String currency, List<String> postedEvents) {

    public static FundPostingResponse from(ChannelFundFact fact, List<String> postedEvents) {
        return new FundPostingResponse(fact.channelCode(), fact.period(), fact.netReceivedMinor(),
                fact.channelFeeMinor(), fact.currency(), postedEvents);
    }
}
