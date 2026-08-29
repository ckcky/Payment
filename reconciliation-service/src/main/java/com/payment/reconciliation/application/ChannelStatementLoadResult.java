package com.payment.reconciliation.application;

import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;

import java.util.List;

/**
 * 渠道账单加载结果（ADR-0020）：同时返回解析出的账单条目与「来源溯源」，
 * 让对账批次能记录「这一批到底对了哪份账单」，且回退有痕迹、绝不静默。
 */
public record ChannelStatementLoadResult(List<ChannelStatement> statements, ChannelStatementSource source) {
}
