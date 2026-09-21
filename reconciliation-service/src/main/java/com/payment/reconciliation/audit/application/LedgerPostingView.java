package com.payment.reconciliation.audit.application;

import com.payment.common.dto.rpc.LedgerDirection;

import java.util.List;

/**
 * 账本交易只读视图（spec 017 / FR-004，031 事件化改造）：来自 ledger
 * {@code GET /internal/ledger/postings/all}（{@code AccountingEventResponse} 回显）。
 *
 * <p>分录携带账本侧解析出的**账户语义**（accountCode / owner 维度）——entry_type 停写后，
 * 审计器按科目语义聚合而非数值 accountId 判定（只读回显，非记账指令）。</p>
 *
 * <p>032/G2：补齐 {@code period} / {@code postedAt} / {@code status}（031 出参已有，
 * 此前映射被丢弃）——审计按期间与时点比对、PERIOD_CLOSED 结果可回显。</p>
 */
public record LedgerPostingView(String postingNo, String eventType, String idempotencyKey,
                                String sourceType, String sourceId, String period, String postedAt,
                                String status, String currency, List<LedgerEntryView> entries) {

    /** 兼容构造（032 前的 9 字段形态）：period/postedAt/status 缺省为 null。 */
    public LedgerPostingView(String postingNo, String eventType, String idempotencyKey,
                             String sourceType, String sourceId, String currency,
                             List<LedgerEntryView> entries) {
        this(postingNo, eventType, idempotencyKey, sourceType, sourceId, null, null, null,
                currency, entries);
    }

    /** 单条分录视图（accountCode 为账本实例的科目定义码）。 */
    public record LedgerEntryView(long accountId, String accountCode, String ownerType, String ownerId,
                                  String direction, long amountMinor) {
    }

    /** posting 借方合计（= 该记账批次的借方总额）。 */
    public long debitTotal() {
        return entries.stream().filter(this::isDebit).mapToLong(LedgerEntryView::amountMinor).sum();
    }

    /** 指定科目（可多实例）的带符号发生额（DEBIT 为正、CREDIT 为负）。 */
    public long signedForCodes(String... accountCodes) {
        return entries.stream()
                .filter(e -> e.accountCode() != null && List.of(accountCodes).contains(e.accountCode()))
                .mapToLong(e -> isDebit(e) ? e.amountMinor() : -e.amountMinor())
                .sum();
    }

    /** 方向判定走事件契约枚举（031 §7.7 门禁：direction 字面量禁落上游调用方）。 */
    private boolean isDebit(LedgerEntryView entry) {
        return LedgerDirection.DEBIT.name().equals(entry.direction());
    }
}
