package com.payment.reconciliation.domain;

/**
 * 对账差异类型（spec 032 §8.2 八类，命名与既有值共存，不改名）：
 * 金额差异、状态差异、平台独有（短款）、渠道独有（长款），
 * 以及 032 新增：手续费差异、渠道重复行、账单行无法归一。
 */
public enum DifferenceType {
    AMOUNT_MISMATCH("HIGH"),
    STATUS_MISMATCH("MEDIUM"),
    PLATFORM_ONLY("HIGH"),
    CHANNEL_ONLY("HIGH"),
    /** 本金一致而手续费不一致（spec 032 §8.2；走 ADJUSTMENT 收口，不补发 CHANNEL_FEE）。 */
    FEE_MISMATCH("MEDIUM"),
    /** 同一 (importId, channelTxnNo, referenceType) 在同一账单内重复出现。 */
    DUPLICATE_CHANNEL("MEDIUM"),
    /** 账单行无法归一（缺商户 / 缺键 / 类型不可识别）——吸收历史静默跳过。 */
    UNKNOWN_MAPPING("LOW");

    private final String severity;

    DifferenceType(String severity) {
        this.severity = severity;
    }

    /** 差异严重度（reconciliation_differences.severity 列；指标 reconciliation.difference{kind,severity}）。 */
    public String severity() {
        return severity;
    }
}
