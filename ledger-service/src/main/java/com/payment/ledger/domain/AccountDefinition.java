package com.payment.ledger.domain;

import com.payment.common.dto.rpc.AccountCode;

import java.util.Objects;

/**
 * 科目定义（Chart-of-Accounts 类型目录，spec 031 §5.1 / ADR-0078）：
 * 由 Java 枚举升级为受版本控制的表 {@code account_definitions}（seed 随 schema 重放，
 * 新增科目 MUST 走 ADR）。Definition ≠ Account：可记账的是 {@link AccountInstance}。
 *
 * <p>{@code normalBalance} 由 type 派生（ASSET/EXPENSE=DEBIT，其余=CREDIT），落列便于推导。
 * {@code code/ownerDimension/status} 与契约枚举 {@link AccountCode} 对齐（seed 一致性由
 * {@code AccountDefinitionValidator} 启动期校验）。</p>
 */
public final class AccountDefinition {

    private final String code;
    private final String name;
    private final AccountType type;
    private final NormalSide normalBalance;
    private final AccountCode.OwnerDimension ownerDimension;
    private final AccountCode.Status status;

    public AccountDefinition(String code, String name, AccountType type, NormalSide normalBalance,
                             AccountCode.OwnerDimension ownerDimension, AccountCode.Status status) {
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.normalBalance = Objects.requireNonNull(normalBalance, "normalBalance");
        this.ownerDimension = Objects.requireNonNull(ownerDimension, "ownerDimension");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public NormalSide getNormalBalance() {
        return normalBalance;
    }

    public AccountCode.OwnerDimension getOwnerDimension() {
        return ownerDimension;
    }

    public AccountCode.Status getStatus() {
        return status;
    }

    public boolean isLegacy() {
        return status == AccountCode.Status.LEGACY;
    }

    /** 科目类型（沿用 Constitution §8 / ADR-0008 的五类）。 */
    public enum AccountType {
        ASSET,
        LIABILITY,
        REVENUE,
        EXPENSE,
        EQUITY
    }

    /** 正常余额方向。 */
    public enum NormalSide {
        DEBIT,
        CREDIT
    }
}
