package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 限额配置持久化实体（PO）：承载 {@code user_payment_limits} 表列。
 * 状态与判定逻辑在 {@code limit.domain.UserPaymentLimit}。
 */
@TableName("user_payment_limits")
public class UserPaymentLimitEntity extends BaseEntity {

    private String userId;
    private String currencyCode;
    private Long dailyLimitMinor;
    private Long monthlyLimitMinor;
    private Long yearlyLimitMinor;
    private String status;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public Long getDailyLimitMinor() {
        return dailyLimitMinor;
    }

    public void setDailyLimitMinor(Long dailyLimitMinor) {
        this.dailyLimitMinor = dailyLimitMinor;
    }

    public Long getMonthlyLimitMinor() {
        return monthlyLimitMinor;
    }

    public void setMonthlyLimitMinor(Long monthlyLimitMinor) {
        this.monthlyLimitMinor = monthlyLimitMinor;
    }

    public Long getYearlyLimitMinor() {
        return yearlyLimitMinor;
    }

    public void setYearlyLimitMinor(Long yearlyLimitMinor) {
        this.yearlyLimitMinor = yearlyLimitMinor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
