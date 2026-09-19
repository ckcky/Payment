package com.payment.payment.limit.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;
import java.time.LocalDate;

/**
 * 周期额度占用持久化实体（PO）：承载 {@code user_limit_usage} 表列。
 *
 * <p><b>注意</b>：金额的变更一律走 {@code LimitUsageMapper} 的原子 UPDATE，
 * <b>不</b>经 MyBatis-Plus 的 {@code updateById}（那是读-改-写，有并发穿透）。
 * 本 PO 只用于查询与建行。</p>
 */
@TableName("user_limit_usage")
public class UserLimitUsageEntity extends BaseEntity {

    private String userId;
    private String currencyCode;
    private String period;
    private LocalDate periodStart;
    private Long usedMinor;
    private Long pendingMinor;

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

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public Long getUsedMinor() {
        return usedMinor;
    }

    public void setUsedMinor(Long usedMinor) {
        this.usedMinor = usedMinor;
    }

    public Long getPendingMinor() {
        return pendingMinor;
    }

    public void setPendingMinor(Long pendingMinor) {
        this.pendingMinor = pendingMinor;
    }
}
