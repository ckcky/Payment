package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 科目定义持久化实体（PO，spec 031 §5.1 / §14①）：受版本控制的 seed 目录，
 * 新增科目 MUST 走 ADR（G4 纪律）。
 */
@TableName("account_definitions")
public class AccountDefinitionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String type;
    /** 正常余额方向：DEBIT / CREDIT（由 type 派生后落列，供规则与展示推导）。 */
    private String normalBalance;
    /** owner 维度：PLATFORM / CHANNEL / MERCHANT（§5.1）。 */
    private String ownerDimension;
    /** ACTIVE / LEGACY。 */
    private String status;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getNormalBalance() {
        return normalBalance;
    }

    public void setNormalBalance(String normalBalance) {
        this.normalBalance = normalBalance;
    }

    public String getOwnerDimension() {
        return ownerDimension;
    }

    public void setOwnerDimension(String ownerDimension) {
        this.ownerDimension = ownerDimension;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
