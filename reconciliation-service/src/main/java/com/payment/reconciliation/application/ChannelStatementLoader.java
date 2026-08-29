package com.payment.reconciliation.application;

/**
 * 渠道账单加载端口：按周期加载渠道侧账单条目，并返回来源溯源（ADR-0020）。
 * 生产用本地 Mock/预置 fixture（CsvChannelStatementLoader），测试用 fake。
 */
public interface ChannelStatementLoader {

    ChannelStatementLoadResult load(String period);
}
