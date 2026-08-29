package com.payment.common.dto.rpc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 记账请求（Feature 004 / FR-001、FR-004）：业务服务 → ledger-service 的内部 RPC 契约。
 *
 * <p>放在 common-dto 以避免业务服务对 ledger-service 产生编译依赖（Constitution §III）。</p>
 *
 * @param idempotencyKey 幂等键（业务提供，如 {@code PAYMENT:<paymentIdempotencyKey>}）
 * @param sourceType     PAYMENT / REFUND / SETTLEMENT
 * @param sourceId       业务来源 ID
 * @param currency       币种（MVP 仅 CNY）
 * @param entries        分录，借贷必须平衡（账本侧强校验，不平衡直接拒绝）
 */
public record PostingRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String sourceType,
        @NotBlank String sourceId,
        @NotBlank String currency,
        @NotEmpty List<EntryRequest> entries) {

    /**
     * 单条分录请求。
     *
     * @param accountId   科目 ID（账本预置科目，见账本科目表）
     * @param direction   DEBIT / CREDIT
     * @param amountMinor 金额（最小货币单位，必须 > 0）
     * @param entryType   PAYMENT_CAPTURE / FEE / REFUND / SETTLEMENT
     */
    public record EntryRequest(
            @NotNull Long accountId,
            @NotBlank String direction,
            @Positive long amountMinor,
            @NotBlank String entryType) {
    }
}
