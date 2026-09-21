package com.payment.common.dto.rpc;

/**
 * 账本科目码（事件契约枚举，spec 031 §5.1 / §7.7，ADR-0078）：科目 code 常量只允许出现在
 * ledger-service 与本契约枚举中——上游引用本枚举表达「账户语义」（仅 ADJUSTMENT
 * 的 from/to 槽使用），MUST NOT 以字符串字面量或数值 accountId 形式散落他域
 * （ArchUnit 门禁承接）。
 *
 * <p>{@code ownerDimension} 与 {@code status} 与 {@code account_definitions} 表 seed 一致，
 * 是账本侧解析账户实例（AccountResolver）的语义锚。新增科目 MUST 走 ADR（G4 纪律）。</p>
 */
public enum AccountCode {

    /** 平台对渠道的应收（按渠道分户）。 */
    CHANNEL_RECEIVABLE(OwnerDimension.CHANNEL, Status.ACTIVE),
    /** 应付商户（按商户分户）。 */
    MERCHANT_PAYABLE(OwnerDimension.MERCHANT, Status.ACTIVE),
    /** 平台银行现金。 */
    BANK_CASH(OwnerDimension.PLATFORM, Status.ACTIVE),
    /** 平台手续费收入（原 PLATFORM_FEE_REVENUE，就地更名，ADR-0078）。 */
    FEE_REVENUE(OwnerDimension.PLATFORM, Status.ACTIVE),
    /** 渠道手续费成本（按渠道分户）。 */
    CHANNEL_FEE_EXPENSE(OwnerDimension.CHANNEL, Status.ACTIVE),
    /** 已结算待出款（按商户分户，§5.1）。 */
    SETTLEMENT_PAYABLE(OwnerDimension.MERCHANT, Status.ACTIVE),
    /** 待处理差错款（挂账）。 */
    SUSPENSE(OwnerDimension.PLATFORM, Status.ACTIVE),
    /** 历史科目：仅承接切换前分录，禁止新事件引用（LEGACY）。 */
    CUSTOMER_CASH(OwnerDimension.PLATFORM, Status.LEGACY);

    /** 实例的 owner 维度：PLATFORM 单例 / 按渠道分户 / 按商户分户。 */
    public enum OwnerDimension {
        PLATFORM,
        CHANNEL,
        MERCHANT
    }

    /** ACTIVE 可被新事件使用；LEGACY 仅承接历史分录。 */
    public enum Status {
        ACTIVE,
        LEGACY
    }

    private final OwnerDimension ownerDimension;
    private final Status status;

    AccountCode(OwnerDimension ownerDimension, Status status) {
        this.ownerDimension = ownerDimension;
        this.status = status;
    }

    public OwnerDimension ownerDimension() {
        return ownerDimension;
    }

    public Status status() {
        return status;
    }

    public boolean isLegacy() {
        return status == Status.LEGACY;
    }
}
