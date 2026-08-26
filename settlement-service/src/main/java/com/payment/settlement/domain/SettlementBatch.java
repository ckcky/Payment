package com.payment.settlement.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结算批次聚合根：商户某结算周期的结算事实（收入/退款/调整/净额）及其生命周期。
 *
 * <p>只记录已确认财务事实与净额，不发起真实打款（MVP 模拟执行）。金额一律最小货币单位（long），
 * 禁止浮点。状态机唯一变更入口即本类方法，终态（SUCCEEDED/FAILED/CLOSED）吸收迟到冲突结果；
 * UNKNOWN 只经权威结果收敛为成功/失败，不得臆断。</p>
 */
public class SettlementBatch {

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String merchantId;
    private final String period;
    private String currencyCode;
    private long incomeMinor;
    private long refundMinor;
    private long adjustmentMinor;
    private long netMinor;
    private SettlementStatus status = SettlementStatus.PENDING;
    private final List<SettlementItem> items;
    private final String idempotencyKey;

    /**
     * 创建新批次（状态 PENDING，金额 0）。金额经 {@link #calculate} 填充。
     */
    public SettlementBatch(String merchantId, String period, String currencyCode, String idempotencyKey) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.period = Objects.requireNonNull(period, "period");
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.items = new ArrayList<>();
    }

    /**
     * 计算金额：净额 = 收入 − 退款 − 调整。净额可为负（MVP 不拒绝）。
     */
    public void compute(long income, long refund, long adjustment, String currency) {
        if (income < 0 || refund < 0 || adjustment < 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    "settlement amounts must be >= 0");
        }
        this.incomeMinor = income;
        this.refundMinor = refund;
        this.adjustmentMinor = adjustment;
        this.netMinor = income - refund - adjustment;
        if (currency != null) {
            this.currencyCode = currency;
        }
    }

    /** PENDING → CALCULATING（填充金额并进入计算中）。 */
    public void calculate(long income, long refund, long adjustment, String currency) {
        requireStatus(SettlementStatus.PENDING, "calculate");
        compute(income, refund, adjustment, currency);
        this.status = SettlementStatus.CALCULATING;
    }

    /** CALCULATING → READY（净额已就绪，可执行）。 */
    public void markReady() {
        requireStatus(SettlementStatus.CALCULATING, "markReady");
        this.status = SettlementStatus.READY;
    }

    /** READY → EXECUTING（进入（模拟）打款执行）。 */
    public void execute() {
        requireStatus(SettlementStatus.READY, "execute");
        this.status = SettlementStatus.EXECUTING;
    }

    /** EXECUTING/UNKNOWN → SUCCEEDED；终态冲突被吸收（返回 false）。 */
    public boolean succeed() {
        return transitionTo(SettlementStatus.SUCCEEDED, "succeed",
                SettlementStatus.EXECUTING, SettlementStatus.UNKNOWN);
    }

    /** EXECUTING/UNKNOWN → FAILED；终态冲突被吸收（返回 false）。 */
    public boolean fail(String reason) {
        return transitionTo(SettlementStatus.FAILED, "fail",
                SettlementStatus.EXECUTING, SettlementStatus.UNKNOWN);
    }

    /** EXECUTING → UNKNOWN（执行结果未知）；终态冲突被吸收（返回 false）。 */
    public boolean markUnknown(String reason) {
        return transitionTo(SettlementStatus.UNKNOWN, "markUnknown", SettlementStatus.EXECUTING);
    }

    /** 成功/失败 → CLOSED（终态关闭）。 */
    public void close() {
        if (status == SettlementStatus.SUCCEEDED || status == SettlementStatus.FAILED) {
            this.status = SettlementStatus.CLOSED;
            return;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal close from " + this.status);
    }

    /**
     * 持久化重建：还原批次聚合及其历史状态，绕过创建期校验（不改变业务规则）。
     */
    public static SettlementBatch rehydrate(Long id, String merchantId, String period, String currencyCode,
                                            long incomeMinor, long refundMinor, long adjustmentMinor, long netMinor,
                                            SettlementStatus status, List<SettlementItem> items,
                                            String idempotencyKey, Integer version) {
        SettlementBatch batch = new SettlementBatch(merchantId, period, currencyCode, idempotencyKey);
        batch.id = id;
        batch.incomeMinor = incomeMinor;
        batch.refundMinor = refundMinor;
        batch.adjustmentMinor = adjustmentMinor;
        batch.netMinor = netMinor;
        batch.status = status;
        batch.version = version;
        if (items != null) {
            batch.items.addAll(items);
        }
        return batch;
    }

    public void addItem(SettlementItem item) {
        this.items.add(Objects.requireNonNull(item, "item"));
    }

    private boolean transitionTo(SettlementStatus target, String op, SettlementStatus... from) {
        if (status == target) {
            return false; // 幂等重复
        }
        for (SettlementStatus s : from) {
            if (status == s) {
                this.status = target;
                return true;
            }
        }
        if (isTerminal()) {
            return false; // 终态吸收迟到冲突结果
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal " + op + " from " + this.status);
    }

    private boolean isTerminal() {
        return status == SettlementStatus.SUCCEEDED
                || status == SettlementStatus.FAILED
                || status == SettlementStatus.CLOSED;
    }

    private void requireStatus(SettlementStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getPeriod() {
        return period;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getIncomeMinor() {
        return incomeMinor;
    }

    public long getRefundMinor() {
        return refundMinor;
    }

    public long getAdjustmentMinor() {
        return adjustmentMinor;
    }

    public long getNetMinor() {
        return netMinor;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public List<SettlementItem> getItems() {
        return items;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
