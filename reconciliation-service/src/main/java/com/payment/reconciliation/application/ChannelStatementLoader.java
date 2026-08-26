package com.payment.reconciliation.application;

import com.payment.reconciliation.domain.ChannelStatement;

import java.util.List;

/**
 * 渠道账单加载端口：按周期加载渠道侧账单条目。
 * 生产用本地 Mock/预置 fixture（CsvChannelStatementLoader），测试用 fake。
 */
public interface ChannelStatementLoader {

    List<ChannelStatement> load(String period);
}
