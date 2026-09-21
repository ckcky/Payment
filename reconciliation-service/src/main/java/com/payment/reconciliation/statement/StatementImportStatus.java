package com.payment.reconciliation.statement;

/**
 * 账单导入状态机（spec 032 §7.1，3 态，无终态回退）：
 * RECEIVED → NORMALIZED（全部行解析成功）｜RECEIVED → REJECTED（任一必需字段缺失 /
 * 表头不识别 / 币种非法 / 结构性解析失败）。REJECTED 不可逆——同周期更正账单 = 重新导入产生新批次。
 */
public enum StatementImportStatus {
    RECEIVED,
    NORMALIZED,
    REJECTED
}
