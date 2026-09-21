package com.payment.common.dto.rpc;

import java.time.Instant;
import java.util.List;

/**
 * 记账事件处理结果（spec 031 §6.2，替换 Feature 004 的 {@code PostingResponse}——一刀切换）：
 * 返回 LedgerTransaction（现 postings）与其分录，供调用方回查与补偿核对。
 */
public record AccountingEventResponse(Long postingId,
                                      String postingNo,
                                      String eventType,
                                      String idempotencyKey,
                                      String sourceType,
                                      String sourceId,
                                      String currency,
                                      String period,
                                      Instant postedAt,
                                      String status,
                                      List<EntryView> entries) {

    /**
     * 分录视图（031 收敛：entry_type 停写，事件语义看 {@code eventType}；
     * 账户语义由账本以**科目码 + owner**回显——只读，不构成调用方的记账指令）。
     */
    public record EntryView(Long id, Long accountId, String accountCode, String ownerType,
                            String ownerId, String direction, long amountMinor, String currency) {
    }
}
