package com.payment.ledger.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountingEventType;

import java.util.Objects;

/**
 * 记账事件（领域模型，spec 031 §6）：入站 {@code AccountingEventRequest} 的领域投影，
 * 承载**已确认的财务事实**（Financial Fact）。它是 Posting Rule 的唯一输入。
 *
 * <p>幂等键由本类比 {@code {eventType}:{sourceId}} 派生（原则 10），MUST NOT 来自契约字段。
 * 槽位按 eventType 的必填表在 {@link #require} 处 fail fast（不猜默认）。</p>
 */
public final class AccountingEvent {

    private final AccountingEventType eventType;
    private final LedgerSourceType sourceType;
    private final String sourceId;
    private final String currency;
    private final Long grossAmountMinor;
    private final Long merchantFeeMinor;
    private final Long channelFeeMinor;
    private final String merchantId;
    private final String channelCode;
    private final Long netAmountMinor;
    private final String adjustmentKind;
    private final String fromAccountCode;
    private final String toAccountCode;
    private final Long amountMinor;
    private final AccountingEventType reversesEventType;
    private final String reversesSourceId;

    public AccountingEvent(AccountingEventType eventType, LedgerSourceType sourceType, String sourceId,
                           String currency, Long grossAmountMinor, Long merchantFeeMinor,
                           Long channelFeeMinor, String merchantId, String channelCode,
                           Long netAmountMinor, String adjustmentKind, String fromAccountCode,
                           String toAccountCode, Long amountMinor,
                           AccountingEventType reversesEventType, String reversesSourceId) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceId = requireText(sourceId, "sourceId");
        this.currency = requireText(currency, "currency");
        this.grossAmountMinor = grossAmountMinor;
        this.merchantFeeMinor = merchantFeeMinor;
        this.channelFeeMinor = channelFeeMinor;
        this.merchantId = merchantId;
        this.channelCode = channelCode;
        this.netAmountMinor = netAmountMinor;
        this.adjustmentKind = adjustmentKind;
        this.fromAccountCode = fromAccountCode;
        this.toAccountCode = toAccountCode;
        this.amountMinor = amountMinor;
        this.reversesEventType = reversesEventType;
        this.reversesSourceId = reversesSourceId;
    }

    /** 派生幂等键（原则 10）：{@code {eventType}:{sourceId}}。 */
    public String derivedIdempotencyKey() {
        return eventType.name() + ":" + sourceId;
    }

    public boolean isPositiveGross() {
        return grossAmountMinor != null && grossAmountMinor > 0;
    }

    /** 费槽位取值（null 安全）：费事实允许缺省 = 0（过渡期上游继续传 0，spec §8）。 */
    public long merchantFeeOrZero() {
        return merchantFeeMinor == null ? 0L : merchantFeeMinor;
    }

    public long channelFeeOrZero() {
        return channelFeeMinor == null ? 0L : channelFeeMinor;
    }

    /** 槽位缺失 fail fast（原则：不猜默认）。 */
    public long requireGross() {
        if (grossAmountMinor == null || grossAmountMinor <= 0) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING,
                    eventType + " requires positive grossAmountMinor");
        }
        return grossAmountMinor;
    }

    public long requireAmount() {
        if (amountMinor == null || amountMinor <= 0) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING,
                    eventType + " requires positive amountMinor");
        }
        return amountMinor;
    }

    public long requireNetSigned() {
        if (netAmountMinor == null) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING,
                    eventType + " requires netAmountMinor");
        }
        return netAmountMinor;
    }

    public String requireMerchantId() {
        return requireText(merchantId, "merchantId");
    }

    public String requireChannelCode() {
        return requireText(channelCode, "channelCode");
    }

    public String requireAdjustmentKind() {
        return requireText(adjustmentKind, "adjustmentKind");
    }

    public String requireFromAccountCode() {
        return requireText(fromAccountCode, "fromAccountCode");
    }

    public String requireToAccountCode() {
        return requireText(toAccountCode, "toAccountCode");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING, "event missing field: " + field);
        }
        return value;
    }

    public AccountingEventType eventType() {
        return eventType;
    }

    public LedgerSourceType sourceType() {
        return sourceType;
    }

    public String sourceId() {
        return sourceId;
    }

    public String currency() {
        return currency;
    }

    public Long grossAmountMinor() {
        return grossAmountMinor;
    }

    public Long merchantFeeMinor() {
        return merchantFeeMinor;
    }

    public Long channelFeeMinor() {
        return channelFeeMinor;
    }

    public String merchantId() {
        return merchantId;
    }

    public String channelCode() {
        return channelCode;
    }

    public Long netAmountMinor() {
        return netAmountMinor;
    }

    public String adjustmentKind() {
        return adjustmentKind;
    }

    public String fromAccountCode() {
        return fromAccountCode;
    }

    public String toAccountCode() {
        return toAccountCode;
    }

    public Long amountMinor() {
        return amountMinor;
    }

    public AccountingEventType reversesEventType() {
        return reversesEventType;
    }

    public String reversesSourceId() {
        return reversesSourceId;
    }
}
