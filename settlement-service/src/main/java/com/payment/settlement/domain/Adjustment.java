package com.payment.settlement.domain;

/**
 * 结算调整项（值对象）：人工/系统对净额的调整，MVP 阶段不参与计算，保留契约。
 *
 * @deprecated 已被 {@link SettlementAdjustment}（ADR-0022 独立建表模型）取代。
 * 本记录为死代码（全项目零引用），保留仅作契约占位，不删除（Constitution §VIII.2 无关改动）；
 * 若负责人授权，可在文档收口阶段一并清理。
 */
@Deprecated(since = "007-settlement", forRemoval = false)
public record Adjustment(String reason, long amountMinor, String currencyCode) {
}
