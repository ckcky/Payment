package com.payment.ledger.domain;

import com.payment.common.dto.rpc.AccountCode;

import java.util.Objects;

/**
 * 账户实例（spec 031 §5.2 / ADR-0078）：{@code accounts} 表就地演进后的真正记账对象，
 * 唯一键 {@code (definition_code, owner_type, owner_id, currency)}。
 *
 * <p>例：{@code CHANNEL_RECEIVABLE + CHANNEL + ALIPAY + CNY} = 支付宝渠道应收账户；
 * {@code MERCHANT_PAYABLE + MERCHANT + M001 + CNY} = M001 商户应付账户。</p>
 */
public final class AccountInstance {

    /** 平台单例的 owner 哨兵值。 */
    public static final String OWNER_PLATFORM = "PLATFORM";
    /** 历史商户应付的哨兵实例（切换前余额承接，spec §5.3；新事件不解析到此实例）。 */
    public static final String OWNER_LEGACY = "LEGACY";

    private final Long id;
    private final String definitionCode;
    private final AccountCode.OwnerDimension ownerType;
    private final String ownerId;
    private final String currency;

    public AccountInstance(Long id, String definitionCode, AccountCode.OwnerDimension ownerType,
                           String ownerId, String currency) {
        this.id = id;
        this.definitionCode = Objects.requireNonNull(definitionCode, "definitionCode");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /** 仅改 id 的副本（开户后回填主键）。 */
    public AccountInstance withId(Long newId) {
        return new AccountInstance(newId, definitionCode, ownerType, ownerId, currency);
    }

    public Long getId() {
        return id;
    }

    public String getDefinitionCode() {
        return definitionCode;
    }

    public AccountCode.OwnerDimension getOwnerType() {
        return ownerType;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }
}
