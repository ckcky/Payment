package com.payment.payment.limit.domain;

/**
 * 额度操作流水（spec 027 / FR-015，ADR-0071 D4）。
 *
 * <p>唯一键 {@code UK(biz_no = paymentNo, op_type)} 是幂等的<b>数据库级</b>兜底（INV-4）：
 * 「状态机终态吸收」挡不住两类问题——① 事务外的额度结算崩了（支付已 SUCCEEDED 但 used 未加
 * → 额度虚高、限额静默失效）；② 补偿扫描重跑。故每种操作落一条流水，撞键即跳过金额变更。</p>
 */
public record LimitOperation(
        Long id,
        String operationNo,
        String bizNo,
        LimitOperationType opType,
        String userId,
        String currencyCode,
        LimitPeriod period,
        long amountMinor,
        java.time.Instant expiresAt,
        java.time.Instant createdAt) {
}
