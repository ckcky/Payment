package com.payment.reconciliation.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 执行对账请求（spec 032 §10.1 run）：{@code period} 必填；{@code channelCode} 缺省 LEGACY 渠道；
 * {@code importNo} 显式指定导入（更正账单重对入口），缺省取该渠道该周期最新 NORMALIZED 导入。
 */
public record RunReconciliationRequest(@NotBlank String period, String channelCode, String importNo) {
}
