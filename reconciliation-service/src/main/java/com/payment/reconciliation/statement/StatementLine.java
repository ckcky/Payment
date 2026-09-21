package com.payment.reconciliation.statement;

/**
 * 标准化账单行（spec 032 §6.2 实体，归 {@link StatementImport} 聚合）：
 * 渠道账单的一行，经 {@code StatementParser} 归一——渠道、商户、费用、发生时间、类型、唯一键齐备。
 * 原始行（rawText）留档以便重解析与争议取证；金额一律 long minor 单位。
 *
 * @param importId      所属导入批次 id
 * @param lineNo        行号（同批次内 1 起连续，uk(import_id, line_no)）
 * @param channelCode   渠道码
 * @param channelTxnNo  渠道流水号（真实渠道账单主键；legacy 4 列账单为 null）
 * @param referenceType 类型：PAYMENT / REFUND / SETTLEMENT / FEE / UNKNOWN（不可识别行）
 * @param reference     引用号（归一化优先级：渠道流水号 &gt; 平台业务单号）
 * @param referenceKind 引用类别：CHANNEL_TXN / PLATFORM_NO / NONE
 * @param merchantId    商户号（缺商户 = 归一缺陷，匹配层产 UNKNOWN_MAPPING）
 * @param amountMinor   金额（最小货币单位，long，禁止浮点）
 * @param feeMinor      手续费（FEE 行为费用本体；PAYMENT/REFUND 行的费用仅参与 FEE_MISMATCH 判定，
 *                      不进 CHANNEL_FEE 聚合——plan §2.6 双计防线）
 * @param currency      币种
 * @param status        渠道侧状态（如 SUCCEEDED / FAILED）
 * @param occurredAt    发生时间（渠道口径，可空）
 * @param rawText       原始行留档
 */
public record StatementLine(Long importId, int lineNo, String channelCode, String channelTxnNo,
                            String referenceType, String reference, String referenceKind,
                            String merchantId, long amountMinor, long feeMinor,
                            String currency, String status, String occurredAt, String rawText) {

    /** 归一缺陷原因（spec 032 §8.2 UNKNOWN_MAPPING 的判定输入；null = 无缺陷）。 */
    public String normalizeDefect() {
        if (merchantId == null || merchantId.isBlank()) {
            return "MISSING_MERCHANT";
        }
        if ((reference == null || reference.isBlank())
                && (channelTxnNo == null || channelTxnNo.isBlank())) {
            return "MISSING_KEY";
        }
        if (referenceType == null || referenceType.isBlank() || "UNKNOWN".equals(referenceType)) {
            return "UNKNOWN_TYPE";
        }
        return null;
    }

    /** 差异引用口径：优先 reference，其次 channelTxnNo，兜底行号（保证差异可追溯、不静默）。 */
    public String differenceReference() {
        if (reference != null && !reference.isBlank()) {
            return reference;
        }
        if (channelTxnNo != null && !channelTxnNo.isBlank()) {
            return channelTxnNo;
        }
        return "line-" + lineNo;
    }
}
