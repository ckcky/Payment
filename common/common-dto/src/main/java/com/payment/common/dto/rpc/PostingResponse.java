package com.payment.common.dto.rpc;

import java.util.List;

/**
 * 记账结果（Feature 004）：账本返回已存在或新落库的 Posting，供调用方回查与核对。
 */
public record PostingResponse(Long postingId,
                              String idempotencyKey,
                              String sourceType,
                              String sourceId,
                              String currency,
                              String status,
                              List<EntryView> entries) {

    /**
     * 分录视图。
     */
    public record EntryView(Long id, Long accountId, String direction, long amountMinor,
                            String currency, String entryType) {
    }
}
