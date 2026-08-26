package com.payment.common.core;

/**
 * 服务模块名常量，用于领域事件的 {@code sourceModule} 与可观测性维度。
 * 与 ADR-0001 的 9 个领域服务一一对应（gateway、ledger-service 本 MVP 延后）。
 */
public final class ModuleNames {

    private ModuleNames() {
    }

    public static final String MERCHANT = "merchant";
    public static final String CATALOG = "catalog";
    public static final String ORDER = "order";
    public static final String PAYMENT = "payment";
    public static final String REFUND = "refund";
    public static final String FULFILLMENT = "fulfillment";
    public static final String ENTITLEMENT = "entitlement";
    public static final String RECONCILIATION = "reconciliation";
    public static final String SETTLEMENT = "settlement";
}
