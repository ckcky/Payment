package com.payment.common.core.id;

/**
 * 业务单号类型：字母前缀作为系统标识（ADR-0062）。
 *
 * <p>单号格式：{@code 前缀(2~4 字母) + 雪花 ID(十进制 18~19 位)}，总长 20~24 字符，
 * 各表单号列统一 {@code VARCHAR(32) + UNIQUE KEY}。四字母前缀（TXRF/PMRF）为
 * spec 019 / ADR-0067 两层退款单引入，用于在同一编号空间区分交易层与支付层退款单。</p>
 */
public enum BusinessNoType {

    /** 交易单（order-service 创建，payment-service 承接） */
    TRANSACTION("TX"),
    /** 订单 */
    ORDER("OR"),
    /** 订单明细（spec 018 / ADR-0066：明细跨服务引用用业务单号，数值主键不出边界 ADR-0063） */
    ORDER_ITEM("OI"),
    /** 支付单 */
    PAYMENT("PM"),
    /** 交易层退款单（spec 019 / ADR-0067：order transaction 层驱动退款的退款单） */
    TRANSACTION_REFUND("TXRF"),
    /** 支付层退款执行单（spec 019 / ADR-0067：payment-service 退款执行单，替代 RF 新增） */
    PAYMENT_REFUND("PMRF"),
    /** 退款单（存量前缀保留：spec 019 起新退款单一律 PMRF，存量 RF 不改写） */
    REFUND("RF"),
    /** 结算批 */
    SETTLEMENT_BATCH("SB"),
    /** 对账批 */
    RECONCILIATION_BATCH("RB"),
    /** 记账流水 */
    LEDGER_POSTING("LP"),
    /** 审计批次（spec 017：四核对作业批次） */
    AUDIT_BATCH("AB"),
    /** 审计调账单（spec 017 / ADR-0065：挂账、调账凭证号） */
    AUDIT_ADJUSTMENT("AD");

    private final String prefix;

    BusinessNoType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /** 前缀是否匹配（外部单号解析/校验用） */
    public boolean matches(String no) {
        return no != null && no.startsWith(prefix);
    }
}
