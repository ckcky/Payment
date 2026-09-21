package com.payment.common.dto.rpc;

import jakarta.validation.constraints.NotBlank;

/**
 * 记账事件请求（spec 031 §6.2 / ADR-0077）：业务服务 → ledger-service 的**唯一**入账契约，
 * 替换原 {@code PostingRequest}（原始分录直传）——全内部契约，一刀切换，不留兼容层。
 *
 * <p>上游只陈述**已确认的财务事实**（Financial Fact）：金额是已算好的数字（minor 单位），
 * 不是费率；科目与借贷方向 MUST NOT 出现在本请求体（原则 1/3），仅 {@code ADJUSTMENT}
 * 的 {@code from/toAccountCode} 例外——那是「从哪到哪」的业务语义槽位，方向由账本推导。</p>
 *
 * <p>幂等：幂等键**不在契约里**，由 Ledger 按 {@code {eventType}:{sourceId}} 派生（原则 10），
 * 重复事件永不产生重复分录。缺槽位按 eventType 的必填表校验，
 * 拒绝 {@code EVENT_FIELD_MISSING}（fail fast，不猜默认）。</p>
 *
 * @param eventType          事件类型（{@link AccountingEventType}）
 * @param sourceType         来源域（{@link AccountingSourceType}）
 * @param sourceId           来源业务单号（ADR-0063：paymentNo/refundNo/batchNo/adjustNo，禁数值 ID）
 * @param currency           币种（MVP 仅 CNY）
 * @param grossAmountMinor   毛额（PAYMENT_CAPTURE / REFUND / CHANNEL_SETTLEMENT 用）
 * @param merchantFeeMinor   商户费（上游已算好的费事实；null=0。REFUND 语义 = 退还的商户费）
 * @param channelFeeMinor    渠道费成本（同上；REFUND 语义 = 退回的渠道费）
 * @param merchantId         商户号（PAYMENT_CAPTURE / REFUND / MERCHANT_SETTLEMENT 必填）
 * @param channelCode        渠道码（PAYMENT_CAPTURE / CHANNEL_* / REFUND 必填）
 * @param netAmountMinor     净额（MERCHANT_SETTLEMENT 批次净额，**带符号**，负值走反向分录；
 *                           CHANNEL_SETTLEMENT 渠道实付净额）
 * @param adjustmentKind     ADJUSTMENT 五类：SUSPEND / SUPPLEMENT / REVERSE / CORRECT / TRANSFER / WRITE_OFF
 * @param fromAccountCode    ADJUSTMENT 转账语义「从 A 移到 B」的 A（科目码，非借贷指令）
 * @param toAccountCode      ADJUSTMENT 转账语义的目标科目 B
 * @param amountMinor        ADJUSTMENT / CHANNEL_FEE 金额
 * @param reversesEventType  REVERSE / CORRECT：被冲销的原事件类型（红冲分录由**账本读原交易取反**生成，
 *                           上游不传方向——§7.6 的实现期修正，见 Implementation Report 冲突 1）
 * @param reversesSourceId   被冲销的原事件 sourceId
 */
public record AccountingEventRequest(
        @NotBlank String eventType,
        @NotBlank String sourceType,
        @NotBlank String sourceId,
        @NotBlank String currency,
        Long grossAmountMinor,
        Long merchantFeeMinor,
        Long channelFeeMinor,
        String merchantId,
        String channelCode,
        Long netAmountMinor,
        String adjustmentKind,
        String fromAccountCode,
        String toAccountCode,
        Long amountMinor,
        String reversesEventType,
        String reversesSourceId) {

    /** 槽位取值（null 安全）：费事实允许缺省 = 0。 */
    public long grossOrZero() {
        return grossAmountMinor == null ? 0L : grossAmountMinor;
    }

    public long merchantFeeOrZero() {
        return merchantFeeMinor == null ? 0L : merchantFeeMinor;
    }

    public long channelFeeOrZero() {
        return channelFeeMinor == null ? 0L : channelFeeMinor;
    }
}
