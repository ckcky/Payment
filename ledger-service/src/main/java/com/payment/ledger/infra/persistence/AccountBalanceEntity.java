package com.payment.ledger.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 余额投影持久化实体（PO，spec 031 §10 / §14⑤）：主键 {@code (account_instance_id, currency)}。
 * 复合主键不走 BaseMapper 通用方法，读写全部经 {@code AccountBalanceMapper} 自定义 SQL。
 */
@TableName("account_balances")
public class AccountBalanceEntity {

    private Long accountInstanceId;
    private String currency;
    private Long debitTotal;
    private Long creditTotal;
    private Long entryCount;
    private Long lastEntryId;
    private Instant updatedAt;

    public Long getAccountInstanceId() {
        return accountInstanceId;
    }

    public void setAccountInstanceId(Long accountInstanceId) {
        this.accountInstanceId = accountInstanceId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getDebitTotal() {
        return debitTotal;
    }

    public void setDebitTotal(Long debitTotal) {
        this.debitTotal = debitTotal;
    }

    public Long getCreditTotal() {
        return creditTotal;
    }

    public void setCreditTotal(Long creditTotal) {
        this.creditTotal = creditTotal;
    }

    public Long getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(Long entryCount) {
        this.entryCount = entryCount;
    }

    public Long getLastEntryId() {
        return lastEntryId;
    }

    public void setLastEntryId(Long lastEntryId) {
        this.lastEntryId = lastEntryId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
